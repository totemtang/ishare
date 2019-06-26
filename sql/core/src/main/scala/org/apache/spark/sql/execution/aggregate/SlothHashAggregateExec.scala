/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.aggregate

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.errors._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.aggregate.SlothAgg.SlothAggregationIterator
import org.apache.spark.sql.execution.streaming.{OffsetSeqMetadata, StatefulOperatorStateInfo, StateStoreWriter, WatermarkSupport}
import org.apache.spark.sql.execution.streaming.state._
import org.apache.spark.sql.internal.SessionState
import org.apache.spark.util.CompletionIterator
import org.apache.spark.util.SerializableConfiguration
import org.apache.spark.util.Utils


case class SlothHashAggregateExec (
    requiredChildDistributionExpressions: Option[Seq[Expression]],
    groupingExpressions: Seq[NamedExpression],
    aggregateExpressions: Seq[AggregateExpression],
    aggregateAttributes: Seq[Attribute],
    resultExpressions: Seq[NamedExpression],
    keyExpressions: Seq[Attribute],
    eventTimeWatermark: Option[Long],
    stateInfo: Option[StatefulOperatorStateInfo],
    child: SparkPlan)
  extends UnaryExecNode with StateStoreWriter with WatermarkSupport {

  private[this] val aggregateBufferAttributes = {
    aggregateExpressions.flatMap(_.aggregateFunction.aggBufferAttributes)
  }

  require(HashAggregateExec.supportsAggregate(aggregateBufferAttributes))

  override lazy val allAttributes: AttributeSeq =
    child.output ++ aggregateBufferAttributes ++ aggregateAttributes ++
      aggregateExpressions.flatMap(_.aggregateFunction.inputAggBufferAttributes)

  // override lazy val metrics = Map(
  //   "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
  //   "peakMemory" -> SQLMetrics.createSizeMetric(sparkContext, "peak memory"),
  //   "spillSize" -> SQLMetrics.createSizeMetric(sparkContext, "spill size"),
  //   "aggTime" -> SQLMetrics.createTimingMetric(sparkContext, "aggregate time"),
  //   "avgHashProbe" -> SQLMetrics.createAverageMetric(sparkContext, "avg hash probe"))

  override def output: Seq[Attribute] = resultExpressions.map(_.toAttribute)

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def producedAttributes: AttributeSet =
    AttributeSet(aggregateAttributes) ++
    AttributeSet(resultExpressions.diff(groupingExpressions).map(_.toAttribute)) ++
    AttributeSet(aggregateBufferAttributes)

  override def requiredChildDistribution: List[Distribution] = {
    requiredChildDistributionExpressions match {
      case Some(exprs) if exprs.isEmpty => AllTuples :: Nil
      case Some(exprs) if exprs.nonEmpty => ClusteredDistribution(exprs) :: Nil
      case None => UnspecifiedDistribution :: Nil
    }
  }

  /** StateStore Infomation */
  private[this] val storeConf = new StateStoreConf(sqlContext.conf)
  private[this] val hadoopConfBcast = sparkContext.broadcast(
    new SerializableConfiguration(SessionState.newHadoopConf(
      sparkContext.hadoopConfiguration, sqlContext.conf)))

  private[this] var aggIter: SlothAggregationIterator = _

  private[this] def onCompletion: Unit = {
    val commitTimeMs = longMetric("commitTimeMs")
    commitTimeMs.set(timeTakenMs{
      if (aggIter != null) aggIter.onCompletion()
    })
  }

  protected override def doExecute(): RDD[InternalRow] = attachTree(this, "execute") {

    child.execute().mapPartitionsWithIndex { (partIndex, iter) =>
      val numOutputRows = longMetric("numOutputRows")
      val numUpdatedStateRows = longMetric("numUpdatedStateRows")
      val numTotalStateRows = longMetric("numTotalStateRows")
      val allUpdatesTimeMs = longMetric("allUpdatesTimeMs")
      val allRemovalsTimeMs = longMetric("allRemovalsTimeMs")
      val commitTimeMs = longMetric("commitTimeMs")
      val stateMemory = longMetric("stateMemory")

      val beforeAgg = System.nanoTime()
      val hasInput = iter.hasNext
      val resIter = if (!hasInput && groupingExpressions.nonEmpty) {
        // This is a grouped aggregate and the input iterator is empty,
        // so return an empty iterator.
        Iterator.empty
      } else {
        val aggregationIterator =
          new SlothAggregationIterator(
            partIndex,
            groupingExpressions,
            aggregateExpressions,
            aggregateAttributes,
            resultExpressions,
            (expressions, inputSchema) =>
              newMutableProjection(expressions, inputSchema, subexpressionEliminationEnabled),
            child.output,
            iter,
            numOutputRows,
            numUpdatedStateRows,
            numTotalStateRows,
            allUpdatesTimeMs,
            allRemovalsTimeMs,
            commitTimeMs,
            stateMemory,
            stateInfo,
            storeConf,
            hadoopConfBcast.value.value,
            watermarkPredicateForKeys,
            watermarkPredicateForData,
            deltaOutput)
        if (!hasInput && groupingExpressions.isEmpty) {
          numOutputRows += 1
          Iterator.single[UnsafeRow](aggregationIterator.outputForEmptyGroupingKeyWithoutInput())
        } else {
          aggIter = aggregationIterator
          aggregationIterator
        }
      }
      allUpdatesTimeMs += (System.nanoTime() - beforeAgg) / 1000000
      CompletionIterator[InternalRow, Iterator[InternalRow]](resIter, onCompletion)
    }
  }

  // all the mode of aggregate expressions
  private val modes = aggregateExpressions.map(_.mode).distinct


  // TODO: shouldRunAnotherBatch is only invoked when noBatchDataExecution is enabled
  override def shouldRunAnotherBatch(newMetadata: OffsetSeqMetadata): Boolean = {
    false
  }

  private[this] var deltaOutput: Boolean = true

  override def setDeltaOutput(isDeltaOutput: Boolean): Unit = {deltaOutput = isDeltaOutput}

  override def verboseString: String = toString(verbose = true)

  override def simpleString: String = toString(verbose = false)

  private def toString(verbose: Boolean): String = {
    val allAggregateExpressions = aggregateExpressions

    val keyString = Utils.truncatedString(groupingExpressions, "[", ", ", "]")
    val functionString = Utils.truncatedString(allAggregateExpressions, "[", ", ", "]")
    val outputString = Utils.truncatedString(output, "[", ", ", "]")
    if (verbose) {
      s"SlothHashAggregate(keys=$keyString, functions=$functionString, output=$outputString)"
    } else {
      s"SlothHashAggregate(keys=$keyString, functions=$functionString)"
    }
  }
}

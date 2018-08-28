/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.local

import com.ibm.aardpfark.spark.ml.SparkSupport
import com.opendatagroup.hadrian.jvmcompiler.PFAEngine
import com.salesforce.op.OpWorkflowModel
import com.salesforce.op.stages.sparkwrappers.generic.SparkWrapperParams
import com.salesforce.op.stages.{OPStage, OpTransformer}
import org.apache.spark.ml.SparkMLSharedParamConstants._
import org.apache.spark.ml.Transformer
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.param.ParamMap
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization

import scala.collection.mutable

/**
 * Enrichment for [[OpWorkflowModel]] to allow local scoring functionality
 */
trait OpWorkflowModelLocal {

  /**
   * Enrichment for [[OpWorkflowModel]] to allow local scoring functionality
   *
   * @param model [[OpWorkflowModel]]
   */
  implicit class RichOpWorkflowModel(model: OpWorkflowModel) {

    private implicit val formats = DefaultFormats

    /**
     * Internal PFA model representation
     *
     * @param inputs mode inputs mappings
     * @param output output mapping
     * @param engine PFA engine
     */
    private case class PFAModel(inputs: Map[String, String], output: (String, String), engine: PFAEngine[AnyRef, AnyRef])

    /**
     * Internal OP model representation
     *
     * @param output output name
     * @param model model instance
     */
    private case class OPModel(output: String, model: OPStage with OpTransformer)

    /**
     * Prepares a score function for local scoring
     *
     * @return score function for local scoring
     */
    def scoreFunction: ScoreFunction = {
      val resultFeatures = model.getResultFeatures().map(_.name).toSet
      val stagesWithIndex = model.stages.zipWithIndex
      val opStages = stagesWithIndex.collect { case (s: OpTransformer, i) => OPModel(s.getOutputFeatureName, s) -> i }
      val sparkStages = stagesWithIndex.filterNot(_._1.isInstanceOf[OpTransformer]).collect {
        case (s: OPStage with SparkWrapperParams[_], i) if s.getSparkMlStage().isDefined =>
          ((s, s.getSparkMlStage().get.asInstanceOf[Transformer].copy(ParamMap.empty)), i)
      }
      val pfaEngines = for {
        ((s, sparkStage), i) <- sparkStages
        inParam = sparkStage.getParam(inputCol.name)
        outParam = sparkStage.getParam(outputCol.name)
        inputs = s.getInputFeatures().map(_.name).map {
          case n if sparkStage.get(inParam).contains(n) => n -> inputCol.name
          case n if sparkStage.get(outParam).contains(n) => n -> outputCol.name
          case n => n -> n
        }.toMap
        output = s.getOutputFeatureName
        _ = sparkStage.set(inParam, inputCol.name).set(outParam, outputCol.name)
        pfaJson = SparkSupport.toPFA(sparkStage, pretty = true)
        pfaEngine = PFAEngine.fromJson(pfaJson).head
      } yield (PFAModel(inputs, (output, outputCol.name), pfaEngine), i)

      val allStages = (opStages ++ pfaEngines).sortBy(_._2)

      row: Map[String, Any] => {
        val rowMap = mutable.Map.empty ++ row
        val transformedRow = allStages.foldLeft(rowMap) {
          case (r, (OPModel(output, stage), i)) =>
            r += output -> stage.transformKeyValue(r.apply)

          case (r, (PFAModel(inputs, output, engine), i)) =>
            val inJson = toPFAJson(r, inputs)
            val engineIn = engine.jsonInput(inJson)
            val result = engine.action(engineIn)
            val (out, outCol) = output
            val resMap = parse(result.toString).extract[Map[String, Any]]
            r += out -> resMap(outCol)
        }
        transformedRow.filterKeys(resultFeatures.contains).toMap
      }
    }

    private def toPFAJson(r: mutable.Map[String, Any], inputs: Map[String, String]): String = {
      // Convert Spark values back into a json convertible Map
      // See [[FeatureTypeSparkConverter.toSpark]] for all possible values - we invert them here
      val in: Map[String, Any] = inputs.map { case (k, v) => (v, r.get(k)) }.mapValues {
        case Some(v: Vector) => v.toArray
        case Some(v: mutable.WrappedArray[_]) => v.toArray(v.elemTag)
        case Some(v: Map[_, _]) => v.mapValues {
          case v: mutable.WrappedArray[_] => v.toArray(v.elemTag)
          case x => x
        }
        case None | Some(null) => null
        case Some(v) => v
      }
      Serialization.write(in)
    }
  }

}

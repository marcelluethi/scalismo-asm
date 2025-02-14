/*
 * Copyright 2015 University of Basel, Graphics and Vision Research Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package scalismo.asm.statisticalmodel

import scalismo.common.PointId
import scalismo.geometry.{_3D, Point}
import scalismo.mesh.TriangleMesh

import scala.collection.immutable

trait SearchPointSampler extends Function2[TriangleMesh[_3D], PointId, immutable.Seq[Point[_3D]]] {}

case class NormalDirectionSearchPointSampler(numberOfPoints: Int, searchDistance: Float) extends SearchPointSampler {

  override def apply(mesh: TriangleMesh[_3D], pointId: PointId): immutable.Seq[Point[_3D]] = {
    val point = mesh.pointSet.point(pointId)
    val interval = searchDistance * 2 / numberOfPoints

    val normalUnnormalized = mesh.vertexNormals(pointId)
    val normal = normalUnnormalized * (1.0 / normalUnnormalized.norm)

    def samplePointsOnNormal(): immutable.Seq[Point[_3D]] = {
      for (i <- -numberOfPoints / 2 to numberOfPoints / 2) yield {
        point + normal * i * interval
      }
    }

    samplePointsOnNormal()
  }
}

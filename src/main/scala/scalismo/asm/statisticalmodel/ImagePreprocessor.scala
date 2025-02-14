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

import breeze.linalg.DenseVector
import io.jhdf.api.Group
import scalismo.asm.statisticalmodel.PreprocessedImage.Type
import scalismo.common.interpolation.BSplineImageInterpolator3D
import scalismo.common.{Domain, Field}
import scalismo.geometry.{_3D, EuclideanVector, Point}
import scalismo.hdf5json.HDFPath
import scalismo.image.DiscreteImage
import scalismo.io.statisticalmodel.{HDF5Reader, HDF5Writer, StatisticalModelReader}
import scalismo.vtk.image.filter.DiscreteImageFilter

import scala.util.{Failure, Success, Try}

/**
 * A preprocessed image, which can be fed to a [[FeatureExtractor]].
 */
object PreprocessedImage {
  sealed trait Type

  object Gradient extends Type
  object Intensity extends Type
}

trait PreprocessedImage extends Field[_3D, DenseVector[Float]] {
  def valueType: Type
}

/**
 * An image preprocessor takes a discrete scalar image, performs any required preprocessing, and returns a
 * PreprocessedImage which will serve as input to a [[FeatureExtractor]].
 *
 * When implementing a custom preprocessor, make sure to define and register an accompanying IO Handler.
 * @see
 *   ImagePreprocessorIOHandler
 * @see
 *   ImagePreprocessorIOHandlers
 */
trait ImagePreprocessor extends Function1[DiscreteImage[_3D, Float], PreprocessedImage] with HasIOMetadata

/**
 * IO Handler for the [[ImagePreprocessor]] type.
 */
trait ImagePreprocessorIOHandler extends IOHandler[ImagePreprocessor]

/**
 * IO Handlers for the [[ImagePreprocessor]] type.
 *
 * Handlers for the built-in [[IdentityImagePreprocessor]] and [[GaussianGradientImagePreprocessor]] are pre-registered.
 */
object ImagePreprocessorIOHandlers extends IOHandlers[ImagePreprocessor, ImagePreprocessorIOHandler] {
  register(IdentityImagePreprocessorIOHandler)
  register(GaussianGradientImagePreprocessorIOHandler)
}

object IdentityImagePreprocessor {
  val IOIdentifier: String = "builtin::Identity"
  val IOMetadata_1_0: IOMetadata = IOMetadata(IOIdentifier, 1, 0)
  val IOMetadata_Default: IOMetadata = IOMetadata_1_0
}

/**
 * The "identity" Preprocessor performs no preprocessing. In other words, this class can be considered an adapter that
 * turns a discrete image into a [[PreprocessedImage]], without modifying the image.
 * @param ioMetadata
 *   IO Metadata
 */
case class IdentityImagePreprocessor(override val ioMetadata: IOMetadata = IdentityImagePreprocessor.IOMetadata_Default)
    extends ImagePreprocessor {
  override def apply(inputImage: DiscreteImage[_3D, Float]): PreprocessedImage = new PreprocessedImage {
    override val valueType = PreprocessedImage.Intensity

    val interpolated = inputImage.interpolate(BSplineImageInterpolator3D[Float](3))

    override def domain: Domain[_3D] = interpolated.domain

    override val f: (Point[_3D]) => DenseVector[Float] = { point =>
      DenseVector(interpolated(point))
    }
  }
}

/**
 * IO Handler for [[IdentityImagePreprocessor]] objects.
 */
object IdentityImagePreprocessorIOHandler extends ImagePreprocessorIOHandler {
  override def identifier: String = IdentityImagePreprocessor.IOIdentifier

  override def load(meta: IOMetadata, h5File: StatisticalModelReader, path: HDFPath): Try[IdentityImagePreprocessor] = {
    meta match {
      case IdentityImagePreprocessor.IOMetadata_1_0 => Success(IdentityImagePreprocessor(meta))
      case _                                        => Failure(new IllegalArgumentException(s"Unable to handle $meta"))
    }
  }

}

object GaussianGradientImagePreprocessor {
  val IOIdentifier: String = "builtin::GaussianGradient"
  val IOMetadata_1_0: IOMetadata = IOMetadata(IOIdentifier, 1, 0)
  val IOMetadata_Default: IOMetadata = IOMetadata_1_0
}

/**
 * Image preprocessor that calculates a gradient image from the input image. The following steps are performed:
 *
 *   1. The image is filtered using a Gaussian filter. The sigma parameter used for the filter is passed through from
 *      <code>stddev</code>. If <code>stddev</code> is <code>0</code>, then no blurring is performed. 2. The resulting
 *      image is interpolated (using B-Spline interpolation of order 1). 3. The resulting image is differentiated to
 *      produce a gradient image.
 *
 * @param stddev
 *   the standard deviation (in millimeters) to use for the gaussian blur filter. Set to 0 to disable blurring.
 * @param ioMetadata
 *   IO Metadata
 */
case class GaussianGradientImagePreprocessor(stddev: Double,
                                             override val ioMetadata: IOMetadata =
                                               GaussianGradientImagePreprocessor.IOMetadata_Default
) extends ImagePreprocessor {
  override def apply(inputImage: DiscreteImage[_3D, Float]): PreprocessedImage = new PreprocessedImage {
    override val valueType = PreprocessedImage.Gradient

    val gradientImage: Field[_3D, EuclideanVector[_3D]] = {
      if (stddev > 0) {
        DiscreteImageFilter.gaussianSmoothing(inputImage, stddev)
      } else {
        inputImage
      }
    }.interpolateDifferentiable(BSplineImageInterpolator3D[Float](1)).differentiate

    override def domain: Domain[_3D] = gradientImage.domain

    override val f: (Point[_3D]) => DenseVector[Float] = { point =>
      gradientImage(point).toFloatBreezeVector
    }
  }
}

/**
 * IO Handler for [[GaussianGradientImagePreprocessor]] objects.
 */
object GaussianGradientImagePreprocessorIOHandler extends ImagePreprocessorIOHandler {
  override def identifier: String = GaussianGradientImagePreprocessor.IOIdentifier

  private val Stddev = "stddev"

  override def load(meta: IOMetadata,
                    h5File: StatisticalModelReader,
                    path: HDFPath
  ): Try[GaussianGradientImagePreprocessor] = {

    meta match {
      case GaussianGradientImagePreprocessor.IOMetadata_1_0 =>
        for {
          stddev <- h5File.readFloat(HDFPath(path, Stddev))
        } yield GaussianGradientImagePreprocessor(stddev, meta)
      case _ => Failure(new IllegalArgumentException(s"Unable to handle $meta"))
    }
  }

  override def save(t: ImagePreprocessor, h5File: HDF5Writer, path: HDFPath): Try[Unit] = {
    t match {
      case g: GaussianGradientImagePreprocessor => h5File.writeFloat(HDFPath(path, Stddev), g.stddev.toFloat)
      case _ => Failure(new IllegalArgumentException(s"Unable to handle ${t.getClass}"))
    }
  }
}

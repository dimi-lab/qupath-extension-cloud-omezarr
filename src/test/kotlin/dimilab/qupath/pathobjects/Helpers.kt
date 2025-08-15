package dimilab.qupath.pathobjects

import com.google.gson.JsonObject
import qupath.lib.geom.Point2
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import qupath.lib.roi.interfaces.ROI
import kotlin.math.round
import kotlin.random.Random

object Helpers {
  val plane: ImagePlane = ImagePlane.getDefaultPlane()

  val random = { -> Random.nextDouble(100.0) }

  fun makeRectangleRoi(): ROI {
    return makeRectangleRoi(random(), random(), random(), random())
  }

  fun makeRectangleRoi(x: Double = 0.0, y: Double = 0.0, width: Double = 10.0, height: Double = 10.0): ROI {
    return ROIs.createRectangleROI(x, y, width, height, plane)
  }

  fun makeAnnotation(name: String = "annotation"): PathObject {
    return makeAnnotation(makeRectangleRoi(), name)
  }

  fun makeAnnotation(roi: ROI, name: String = "annotation"): PathObject {
    return PathObjects.createAnnotationObject(roi).also { it.name = name }
  }
}

fun JsonObject.geomToPoints(): List<Point2> {
  val coordsList = this["coordinates"].asJsonArray.first().asJsonArray
  val coords = coordsList.map { pair ->
    val doubles = pair.asJsonArray.map { it.asDouble }
    Point2(doubles[0], doubles[1])
  }

  return coords
}

fun Double.round(decimals: Int): Double {
  var multiplier = 1.0
  repeat(decimals) { multiplier *= 10 }
  return round(this * multiplier) / multiplier
}

fun ROI.pointsLoop(): List<Point2> {
  return (this.allPoints + this.allPoints[0]).map {
    Point2(it.x.round(2), it.y.round(2))
  }
}

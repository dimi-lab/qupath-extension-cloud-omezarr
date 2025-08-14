package dimilab.qupath.pathobjects.changes

import com.google.gson.JsonObject
import dimilab.qupath.pathobjects.toJsonObject
import qupath.lib.geom.Point2
import qupath.lib.io.GsonTools
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import qupath.lib.roi.interfaces.ROI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrackerTest {
  val plane: ImagePlane = ImagePlane.getDefaultPlane()
  val roi1: ROI = ROIs.createRectangleROI(0.0, 0.0, 100.0, 100.0, plane)
  val roi2: ROI = ROIs.createRectangleROI(1.0, 2.0, 100.0, 100.0, plane)
  private val gson = GsonTools.getInstance()

  fun geometryToPoints(geomJson: JsonObject): List<Point2> {
    val coordsList = geomJson["coordinates"].asJsonArray.first().asJsonArray
    val coords = coordsList.map { pair ->
      val doubles = pair.asJsonArray.map { it.asDouble }
      Point2(doubles[0], doubles[1])
    }

    return coords
  }

  @Test
  fun testDiffAnnotationRectangles() {
    val po1 = PathObjects.createAnnotationObject(roi1)
    po1.name = "po1"
    val po2 = PathObjects.createAnnotationObject(roi2)
    po2.name = "po2"
    po2.id = po1.id

    val oldJsonStr = gson.toJson(po1)
    val newJsonStr = gson.toJson(po2)
    val oldObject = gson.fromJson(oldJsonStr, JsonObject::class.java)
    val newObject = gson.fromJson(newJsonStr, JsonObject::class.java)
    val diffs = Tracker().diffJsonObjects(oldObject, newObject)

    assertEquals(setOf("geometry", "properties"), diffs.keys)
    diffs["geometry"]?.asJsonObject?.let { geomJson ->
      val type = geomJson.get("type").asString
      assertEquals("Polygon", type)
      val coords = geometryToPoints(geomJson)
      assertEquals(roi2.allPoints + roi2.allPoints[0], coords)
    }

    diffs["properties"]?.asJsonObject?.let { propsJson ->
      val name = propsJson.get("name").asString
      assertEquals("po2", name)
    }
  }

  @Test
  fun testGenerateChangeEvents() {
    val changedObjOld = PathObjects.createAnnotationObject(roi2)
    changedObjOld.name = "po1"
    val changedObjNew = PathObjects.createAnnotationObject(roi1)
    changedObjNew.name = "po2"
    changedObjNew.id = changedObjOld.id

    val deletedObj = PathObjects.createAnnotationObject(roi1)
    deletedObj.name = "deleted_po"
    val createdObj = PathObjects.createAnnotationObject(roi2)
    createdObj.name = "added_po"

    val tracker = Tracker()
    tracker.trackedObjects.putAll(
      listOf(
        changedObjOld.id to changedObjOld.toJsonObject(),
        deletedObj.id to deletedObj.toJsonObject()
      )
    )
    val newTrackedObjects = mapOf(
      changedObjNew.id to changedObjNew,
      createdObj.id to createdObj
    )

    val changes = tracker.trackBulkChanges(newTrackedObjects)
    assertEquals(3, changes.size)

    val editEvent = changes.find { it.eventType == "edit" }
    assertIs<EditEvent>(editEvent)
    assert(editEvent.id == changedObjOld.id)
    val diff = editEvent.diff
    assertEquals(roi1.allPoints + roi1.allPoints[0], geometryToPoints(diff.get("geometry").asJsonObject))
    assertEquals(setOf("geometry", "properties"), diff.keySet())

    val deleteEvent = changes.find { it.eventType == "delete" }
    assertIs<DeleteEvent>(deleteEvent)
    assert(deleteEvent.id == deletedObj.id)

    val createEvent = changes.find { it.eventType == "create" }
    assertIs<CreateEvent>(createEvent)
    assert(createEvent.id == createdObj.id)
    assertEquals(roi2.allPoints + roi2.allPoints[0], geometryToPoints(createEvent.fields.get("geometry").asJsonObject))
  }
}
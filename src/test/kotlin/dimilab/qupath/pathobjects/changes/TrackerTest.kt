package dimilab.qupath.pathobjects.changes

import dimilab.qupath.pathobjects.Helpers.makeAnnotation
import dimilab.qupath.pathobjects.Helpers.makeRectangleRoi
import dimilab.qupath.pathobjects.geomToPoints
import dimilab.qupath.pathobjects.pointsLoop
import dimilab.qupath.pathobjects.toJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrackerTest {
  @Test
  fun testDiffAnnotationRectangles() {
    val po1 = makeAnnotation("po1")
    val po2 = makeAnnotation("po2").also { it.id = po1.id }

    val oldObject = po1.toJsonObject()
    val newObject = po2.toJsonObject()
    val diffs = Tracker().diffJsonObjects(oldObject, newObject)

    assertEquals(setOf("geometry", "properties"), diffs.keys)
    diffs["geometry"]?.asJsonObject?.let { geomJson ->
      assertEquals("Polygon", geomJson.get("type").asString)
      assertEquals(po2.roi.pointsLoop(), geomJson.geomToPoints())
    }

    diffs["properties"]?.asJsonObject?.let { propsJson ->
      val name = propsJson.get("name").asString
      assertEquals("po2", name)
    }
  }

  @Test
  fun testTrackBulkChanges() {
    val changedObjOld = makeAnnotation("po1")
    val changedObjNew = makeAnnotation("po2").also { it.id = changedObjOld.id }

    val deletedObj = makeAnnotation(makeRectangleRoi(), "deleted_po")
    val createdObj = makeAnnotation(makeRectangleRoi(), "created_po")

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
    assertEquals(changedObjNew.roi.pointsLoop(), diff.getAsJsonObject("geometry").geomToPoints())
    assertEquals(setOf("geometry", "properties"), diff.keySet())

    val deleteEvent = changes.find { it.eventType == "delete" }
    assertIs<DeleteEvent>(deleteEvent)
    assert(deleteEvent.id == deletedObj.id)

    val createEvent = changes.find { it.eventType == "create" }
    assertIs<CreateEvent>(createEvent)
    assert(createEvent.id == createdObj.id)
    assertEquals(createdObj.roi.pointsLoop(), createEvent.fields.getAsJsonObject("geometry").geomToPoints())
  }
}
package dimilab.qupath.pathobjects.changes

import com.google.gson.JsonObject
import dimilab.qupath.pathobjects.Helpers.makeAnnotation
import dimilab.qupath.pathobjects.Helpers.makeRectangleRoi
import dimilab.qupath.pathobjects.geomToPoints
import dimilab.qupath.pathobjects.pointsLoop
import dimilab.qupath.pathobjects.toJsonObject
import qupath.lib.objects.PathObjects
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TrackerTest {
  @Test
  fun testDiffAnnotationRectangles() {
    val po1 = makeAnnotation("po1")
    val po2 = makeAnnotation("po2").also { it.id = po1.id }

    val oldObject = po1.toJsonObject()
    val newObject = po2.toJsonObject()
    val diffs = Tracker().diffJsonObjects(oldObject, newObject)

    assertEquals(setOf("geometry", "properties"), diffs.keys)
    diffs["geometry"]!!.asJsonObject.let { geomJson ->
      assertEquals("Polygon", geomJson.get("type").asString)
      assertEquals(po2.roi.pointsLoop(), geomJson.geomToPoints())
    }

    diffs["properties"]!!.asJsonObject.let { propsJson ->
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
        deletedObj.id to deletedObj.toJsonObject(),
      )
    )
    val newTrackedObjects = mapOf(
      changedObjNew.id to changedObjNew,
      createdObj.id to createdObj,
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

  @Test
  fun testRetrackObjects() {
    // Create a hierarchy with some objects
    val hierarchy = PathObjectHierarchy()
    val ann1Hierarchy = makeAnnotation("fromHierarchy1")
    val ann2Hierarchy = makeAnnotation("fromHierarchy2")
    val det1 = PathObjects.createDetectionObject(makeRectangleRoi())
    hierarchy.addObjects(listOf(ann1Hierarchy, ann2Hierarchy, det1))

    // Create a tracker with different object states
    val tracker = Tracker()
    val ann1Tracker = makeAnnotation("fromTracker1").also { it.id = ann1Hierarchy.id }.toJsonObject()
    val ann2Tracker = makeAnnotation("fromTracker2").also { it.id = ann2Hierarchy.id }.toJsonObject()
    val deletedId = UUID.randomUUID()
    val nonEmpty = JsonObject()
    tracker.trackedObjects.putAll(
      mapOf(
        ann1Hierarchy.id to ann1Tracker,
        ann2Hierarchy.id to ann2Tracker,
        det1.id to nonEmpty,
        deletedId to JsonObject(),
      )
    )

    // Retrack ann1, det1, and deletedId (not ann2)
    tracker.retrackObjects(listOf(ann1Hierarchy.id, det1.id, deletedId), hierarchy)

    // ann1 should be updated from the tracker to the hierarchy version
    assertEquals(ann1Hierarchy.toJsonObject(), tracker.trackedObjects[ann1Hierarchy.id])

    // det1 should be present with empty json
    assertEquals(JsonObject(), tracker.trackedObjects[det1.id])

    // deletedId should be removed
    assertFalse(tracker.trackedObjects.containsKey(deletedId))

    // ann2 should remain unchanged (we did not retrack it)
    assertEquals(ann2Tracker, tracker.trackedObjects[ann2Hierarchy.id])
  }
}
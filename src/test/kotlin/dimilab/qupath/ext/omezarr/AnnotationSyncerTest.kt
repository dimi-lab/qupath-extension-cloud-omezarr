package dimilab.qupath.ext.omezarr

import dimilab.qupath.pathobjects.changes.TrackerTest
import dimilab.qupath.pathobjects.changes.CreateEvent
import dimilab.qupath.pathobjects.changes.DeleteEvent
import dimilab.qupath.pathobjects.changes.EditEvent
import qupath.lib.objects.PathObjects
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs


class AnnotationSyncerTest {
  @Test
  fun testHierarchyChanged() {
    val syncer = AnnotationSyncer()
    val hierarchy = PathObjectHierarchy()
    val newData = qupath.lib.images.ImageData<BufferedImage>(null, hierarchy)
    syncer.imageDataChanged(null, null, newData)

    val annotation = PathObjects.createAnnotationObject(TrackerTest().roi1)
    annotation.name = "initial"
    hierarchy.addObject(annotation)

    annotation.name = "second"
    hierarchy.fireObjectsChangedEvent(this, listOf(annotation))

    hierarchy.removeObject(annotation, false)

    val events = syncer.trackedChanges
    assertEquals(3, events.size)

    val createEvent = events[0]
    assertIs<CreateEvent>(createEvent)
    assert(createEvent.id == annotation.id)
    assert(createEvent.fields["properties"].asJsonObject["name"].asString == "initial")

    val editEvent = events[1]
    assertIs<EditEvent>(editEvent)
    assert(editEvent.id == annotation.id)
    assertEquals(setOf("properties"), editEvent.diff.keySet())
    assert(editEvent.diff.get("properties").asJsonObject.get("name").asString == "second")

    val deleteEvent = events[2]
    assertIs<DeleteEvent>(deleteEvent)
    assert(deleteEvent.id == annotation.id)
  }
}
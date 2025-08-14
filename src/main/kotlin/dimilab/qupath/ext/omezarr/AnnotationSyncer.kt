package dimilab.qupath.ext.omezarr

import dimilab.qupath.pathobjects.changes.Tracker
import dimilab.qupath.pathobjects.changes.Event
import qupath.lib.gui.viewer.QuPathViewer
import qupath.lib.gui.viewer.QuPathViewerListener
import qupath.lib.images.ImageData
import qupath.lib.objects.PathObject
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent.HierarchyEventType
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener
import java.awt.Shape
import java.awt.image.BufferedImage

// This bridges qupath hierarchy events into our change tracking.
class AnnotationSyncer : QuPathViewerListener, PathObjectHierarchyListener {
  companion object {
    private val logger = org.slf4j.LoggerFactory.getLogger(AnnotationSyncer::class.java)
  }

  var trackedHierarchy: PathObjectHierarchy? = null
  val changeTracker = Tracker()
  val trackedChanges = mutableListOf<Event>()

  var paused: Boolean = false

  override fun imageDataChanged(
    viewer: QuPathViewer?,
    imageDataOld: ImageData<BufferedImage>?,
    imageDataNew: ImageData<BufferedImage>?,
  ) {
    logger.info("imageDataChanged: retracking hierarchy")
    trackedHierarchy?.removeListener(this)
    trackedHierarchy = imageDataNew?.hierarchy.also {
      changeTracker.retrack(it)
      it?.addListener(this)
    }
  }

  override fun hierarchyChanged(event: PathObjectHierarchyEvent?) {
    if (paused) return
    if (event == null) return

    // Don't bother with changes that are still happening.
    if (event.isChanging) return

    // If we don't think we're tracking a hierarchy, complain loudly!
    if (trackedHierarchy == null) {
      logger.error("Received hierarchyChanged event but no hierarchy is being tracked!")
      return
    }

    logger.info("Hierarchy change event: ${event.eventType}")

    val changeEvents = when (event.eventType) {
      HierarchyEventType.ADDED, HierarchyEventType.CHANGE_MEASUREMENTS, HierarchyEventType.CHANGE_CLASSIFICATION, HierarchyEventType.CHANGE_OTHER -> {
        changeTracker.trackObjectChanges(event.changedObjects)
      }
      HierarchyEventType.REMOVED -> {
        changeTracker.trackObjectDeletions(event.changedObjects)
      }
      HierarchyEventType.OTHER_STRUCTURE_CHANGE -> {
        val newObjects = event.hierarchy.getAllObjects(false).associateBy { it.id }
        changeTracker.trackBulkChanges(newObjects)
      }
      else -> {
        logger.error("Don't know how to handle hierarchy change event: ${event.eventType}")
        listOf()
      }
    }

    logger.info("Detected ${changeEvents.size} changed hierarchy objects")

    changeEvents
      .groupBy { it.javaClass.name }
      .forEach { (eventType, events) ->
        logger.info("Changes of type $eventType: ${events.size}")
      }

    trackedChanges.addAll(changeEvents)
  }

  override fun visibleRegionChanged(viewer: QuPathViewer?, shape: Shape?) {
  }

  override fun selectedObjectChanged(viewer: QuPathViewer?, pathObjectSelected: PathObject?) {
  }

  override fun viewerClosed(viewer: QuPathViewer?) {
  }

}
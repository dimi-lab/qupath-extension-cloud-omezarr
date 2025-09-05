package dimilab.qupath.ext.omezarr

import dimilab.qupath.pathobjects.changes.*
import javafx.beans.InvalidationListener
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
class AnnotationSyncer : QuPathViewerListener, PathObjectHierarchyListener, StoreListener {
  companion object {
    private val logger = org.slf4j.LoggerFactory.getLogger(AnnotationSyncer::class.java)
    const val LAST_CHANGESET_ID = "dimilab.omezarr.lastChangesetId"
  }

  var remoteStore: CloudStorageStore? = null
  var trackedImage: ImageData<BufferedImage>? = null
  var trackedHierarchy: PathObjectHierarchy? = null
  val changeTracker = Tracker()
  val trackedChanges = mutableListOf<Event>()

  // If true, ignore incoming hierarchy changes.
  var paused: Boolean = false

  var viewerDisplayListener: InvalidationListener? = null

  // Called by QuPath when a viewer's image data changes.
  override fun imageDataChanged(
    viewer: QuPathViewer?,
    imageDataOld: ImageData<BufferedImage>?,
    imageDataNew: ImageData<BufferedImage>?,
  ) {
    logger.info("imageDataChanged: tracking new imageData {}", imageDataNew?.server?.metadata?.name ?: "<unknown>")
    trackedImage = imageDataNew

    trackedHierarchy?.removeListener(this)
    trackedHierarchy = null

    if (remoteStore != null) {
      logger.info("Disconnecting from remote store at: {}", remoteStore?.changesetRoot()?.gsUri)
      remoteStore?.removeListener(this)
      remoteStore = null
    }

    trackedHierarchy = imageDataNew?.hierarchy
    if (trackedHierarchy == null) {
      changeTracker.trackedObjects.clear()
    } else {
      changeTracker.retrack(trackedHierarchy)
    }
    trackedHierarchy?.addListener(this)

    val server = imageDataNew?.server
    if (server is CloudOmeZarrServer && server.serverArgs.changesetRoot != null) {
      val lastChangesetId = (trackedImage?.getProperty(LAST_CHANGESET_ID) as Int?) ?: 0
      logger.info(
        "Connecting to remote changeset store from position {} at: {}",
        lastChangesetId,
        server.serverArgs.changesetRoot
      )
      remoteStore = CloudStorageStore(server.serverArgs.changesetRoot, lastSeenChangesetId = lastChangesetId).also {
        it.addListener(this)
        it.syncEvents(blocking = false)
      }
    } else {
      logger.info("Not connecting image without changeset root: ${imageDataNew?.server?.javaClass?.name}")
    }

    if (server is CloudOmeZarrServer && viewerDisplayListener == null) {
      logger.info("Connecting image display cache-busting listener")
      viewerDisplayListener = InvalidationListener {
        logger.debug("Clearing image region store cache")
        viewer?.imageRegionStore?.clearCache()
      }
      viewer?.imageDisplay?.changeTimestampProperty()?.addListener(viewerDisplayListener)
    } else {
      viewerDisplayListener?.let {
        logger.info("Disconnecting image display listener")
        viewer?.imageDisplay?.changeTimestampProperty()?.removeListener(it)
        viewerDisplayListener = null
      }
    }
  }

  // Called by the CloudStorageStore when it receives new events.
  override fun onNewEvents(events: List<Event>) {
    val trackedHierarchy = this.trackedHierarchy ?: return

    logger.info("Received {} new events from remote store", events.size)

    val oldPaused = this.paused
    this.paused = true
    Applier.applyEventsToHierarchy(events, trackedHierarchy)
    this.paused = oldPaused
    changeTracker.retrackObjects(events.map { it.id }.toSet(), trackedHierarchy)
  }

  override fun onNewChangesetId(changesetId: Int) {
    logger.info("Annotation syncer now at changeset $changesetId")
    trackedImage?.setProperty(LAST_CHANGESET_ID, changesetId)
  }

  // Called by QuPath when an image's object hierarchy changes.
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
    remoteStore?.storeEvents(changeEvents)
  }

  override fun visibleRegionChanged(viewer: QuPathViewer?, shape: Shape?) {
  }

  override fun selectedObjectChanged(viewer: QuPathViewer?, pathObjectSelected: PathObject?) {
  }

  override fun viewerClosed(viewer: QuPathViewer?) {
    viewerDisplayListener?.let {
      viewer?.imageDisplay?.changeTimestampProperty()?.removeListener(it)
      viewerDisplayListener = null
    }
  }

}
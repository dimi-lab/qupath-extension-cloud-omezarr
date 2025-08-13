package dimilab.qupath.pathobjects

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.slf4j.LoggerFactory
import qupath.lib.io.GsonTools
import qupath.lib.objects.PathDetectionObject
import qupath.lib.objects.PathObject
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import java.time.Instant
import java.util.*

sealed class Event {
  abstract val id: UUID
  abstract val timestamp: Instant
  abstract val eventType: String
}

data class CreateEvent(
  override val id: UUID,
  override val timestamp: Instant = Instant.now(),
  // A JSON object mapping the created object's fields to their values
  val fields: JsonObject,
  override val eventType: String = "create",
) : Event()

data class EditEvent(
  override val id: UUID,
  override val timestamp: Instant = Instant.now(),
  // A JSON object mapping the changed fields to their new values
  val diff: JsonObject,
  override val eventType: String = "edit",
) : Event()

data class DeleteEvent(
  override val id: UUID,
  override val timestamp: Instant = Instant.now(),
  override val eventType: String = "delete",
) : Event()

class ChangeTracker {
  private val logger = LoggerFactory.getLogger(ChangeTracker::class.java)
  private val gson = GsonTools.getInstance()
  val trackedObjects = mutableMapOf<UUID, JsonObject>()

  fun retrack(newHierarchy: PathObjectHierarchy?) {
    trackedObjects.clear()

    if (newHierarchy == null) {
      return
    }

    // The conversion to json should be parallelized… but,
    // excluding detection objects makes it way faster!
    newHierarchy.getAllObjects(false).forEach { pathObject ->
      // We don't track detection object changes, so don't track anything beyond existence
      if (pathObject is PathDetectionObject) {
        trackedObjects[pathObject.id] = JsonObject() // just so we know it's there
        return@forEach
      }
      trackedObjects[pathObject.id] = pathObject.toJsonObject()
    }
    logger.info("Now tracking ${trackedObjects.size} objects; hierarchy root is: ${newHierarchy.rootObject.id}")
  }

  private fun makeCreateEvent(id: UUID, newJson: JsonObject): CreateEvent {
    return CreateEvent(
      id = id,
      timestamp = Instant.now(),
      fields = newJson
    )
  }

  private fun makeEditEvent(
    id: UUID,
    oldJson: JsonObject,
    newJson: JsonObject,
    now: Instant = Instant.now(),
  ): EditEvent {
    val changes = diffJsonObjects(oldJson, newJson)

    return EditEvent(
      id = id,
      timestamp = now,
      diff = gson.toJsonTree(changes).asJsonObject
    )
  }

  fun diffJsonObjects(oldJson: JsonObject, newJson: JsonObject): Map<String, JsonElement> {
    return newJson.entrySet().mapNotNull { (key, newValue) ->
      val oldValue = oldJson.get(key)
      if (oldValue != newValue) {
        key to newValue
      } else {
        null
      }
    }.toMap()
  }

  fun trackObjectChanges(pathObjects: Collection<PathObject>): List<Event> {
    return pathObjects.map { pathObject ->
      val oldJson = trackedObjects[pathObject.id]
      val newJson = pathObject.toJsonObject()

      trackedObjects[pathObject.id] = newJson

      if (oldJson == null) {
        logger.debug("Tracking object creation: {}", pathObject.id)
        makeCreateEvent(pathObject.id, newJson)
      } else {
        logger.debug("Tracking object edit: {}", pathObject.id)
        makeEditEvent(pathObject.id, oldJson, newJson)
      }
    }
  }

  fun trackObjectDeletions(pathObjects: Collection<PathObject>): List<Event> {
    return pathObjects.map { pathObject ->
      logger.debug("Tracking object deletion: {}", pathObject.id)
      trackedObjects.remove(pathObject.id)
      DeleteEvent(pathObject.id)
    }
  }

  fun trackBulkChanges(newObjects: Map<UUID, PathObject>): List<Event> {
    logger.info("Tracking bulk hierarchy change. Previously tracking: ${trackedObjects.size} objects. Now tracking: ${newObjects.size} objects")
    val events = mutableListOf<Event>()
    val now = Instant.now() // don't skew "now" for grouped events

    val newJsons = mutableMapOf<UUID, JsonObject>()

    logger.debug("Finding added objects")
    newObjects.forEach { (id, obj) ->
      if (!trackedObjects.containsKey(id)) {
        logger.trace("Found added object: {}", obj.javaClass)
        val newJson = obj.toJsonObject()
        events.add(
          CreateEvent(
            id = id,
            timestamp = now,
            fields = newJson,
          )
        )
        newJsons[id] = newJson
      }
    }

    logger.debug("Finding deleted & changed objects")
    // This should perhaps be parallelized for large hierarchies
    // But… excluding detection objects makes it way faster!
    trackedObjects.forEach { (id, oldJson) ->
      val newObject = newObjects[id]

      if (newObject == null) {
        logger.trace("Found deleted object: {}", trackedObjects[id]?.javaClass)
        events.add(DeleteEvent(id, now))
      } else if (newObject.isRootObject) {
        // No need to compare the root: we're comparing all the children!
        return@forEach
      } else if (newObject is PathDetectionObject) {
        // As of QuPath 0.5.1, detection objects don't seem to get *edited* in bulk –
        // but they do get deleted in bulk (handled above). Note that we don't track
        // detection object changes at all.
        return@forEach
      } else {
        val newJson = newObject.toJsonObject()
        if (oldJson != newJson) {
          logger.trace("Found modified object: {}", newObject.javaClass)

          events.add(makeEditEvent(id, oldJson, newJson, now))
          newJsons[id] = newJson
        }
      }
    }

    // Handled separately to avoid modifying the map during iteration
    events.forEach { event ->
      when (event) {
        is CreateEvent -> trackedObjects[event.id] = newJsons[event.id]!!
        is EditEvent -> trackedObjects[event.id] = newJsons[event.id]!!
        is DeleteEvent -> trackedObjects.remove(event.id)
      }
    }

    logger.debug("Found ${events.size} bulk changes")
    return events
  }
}
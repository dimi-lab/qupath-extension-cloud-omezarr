package dimilab.qupath.pathobjects

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.slf4j.LoggerFactory
import qupath.lib.io.GsonTools
import qupath.lib.objects.PathObject
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

object Generator {
  private val logger = LoggerFactory.getLogger(Generator::class.java)
  private val gson = GsonTools.getInstance()

  fun makeCreateEvent(id: UUID, newJson: JsonObject): CreateEvent {
    return CreateEvent(
      id = id,
      timestamp = Instant.now(),
      fields = newJson
    )
  }

  fun makeEditEvent(id: UUID, oldJson: JsonObject, newJson: JsonObject, now: Instant = Instant.now()): EditEvent {
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

  // Generate a list of change events for two sets of objects, in bulk.
  fun generateChangeEvents(
    trackedObjects: Map<UUID, JsonObject>,
    newObjects: Map<UUID, PathObject>,
  ): List<Event> {
    val events = mutableListOf<Event>()
    val now = Instant.now() // don't skew "now" for grouped events

    logger.debug("Finding added objects")
    newObjects.forEach { (id, obj) ->
      if (!trackedObjects.containsKey(id)) {
        val newJson = gson.toJson(obj)
        logger.trace("Found added object: {}", obj.javaClass)
        events.add(
          CreateEvent(
            id = id,
            timestamp = now,
            fields = gson.fromJson(newJson, JsonObject::class.java)
          )
        )
      }
    }

    logger.debug("Finding deleted & changed objects")
    // This should probably be parallelized for large hierarchies
    trackedObjects.forEach { (id, oldJson) ->
      val newObject = newObjects[id]

      if (newObject == null) {
        logger.trace("Found deleted object: {}", trackedObjects[id]?.javaClass)
        events.add(DeleteEvent(id, now))
      } else {
        // Compare objects by converting them to JSON and comparing the trees
        if (oldJson != newObject) {
          logger.trace("Found modified object: {}", newObject.javaClass)

          events.add(makeEditEvent(id, oldJson, newJson, now))
        }
      }
    }

    logger.debug("Found ${events.size} bulk changes")
    return events
  }
}
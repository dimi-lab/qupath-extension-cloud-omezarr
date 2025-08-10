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

  fun makeCreateEvent(obj: PathObject): CreateEvent {
    val newJson = gson.toJson(obj)
    return CreateEvent(
      id = obj.id,
      timestamp = Instant.now(),
      fields = gson.fromJson(newJson, JsonObject::class.java)
    )
  }

  fun diffPathObjects(oldObject: JsonObject, newObject: JsonObject): Map<String, JsonElement> {
    return newObject.entrySet().mapNotNull { (key, newValue) ->
      val oldValue = oldObject.get(key)
      if (oldValue != newValue) {
        key to newValue
      } else {
        null
      }
    }.toMap()
  }

  fun makeEditEvent(newObj: PathObject, oldObj: PathObject?): EditEvent {
    val newJson = gson.fromJson(gson.toJson(newObj), JsonObject::class.java)

    val changes = oldObj?.let {
      val oldJson = gson.fromJson(gson.toJson(it), JsonObject::class.java)
      diffPathObjects(oldJson, newJson)
    } ?: newJson.entrySet().associate { it.key to it.value }

    return EditEvent(
      id = newObj.id,
      timestamp = Instant.now(),
      diff = gson.toJsonTree(changes).asJsonObject,
    )
  }

  fun generateChangeEvents(
    trackedObjects: Map<UUID, PathObject>,
    newObjects: Map<UUID, PathObject>,
  ): List<Event> {
    val events = mutableListOf<Event>()
    val now = Instant.now()

    // Find added objects
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

    // Find deleted and edited objects
    trackedObjects.forEach { (id, oldObj) ->
      val newObject = newObjects[id]

      if (newObject == null) {
        logger.trace("Found deleted object: {}", trackedObjects[id]?.javaClass)
        events.add(DeleteEvent(id, now))
      } else {
        // Compare objects by converting them to JSON and comparing the trees
        if (oldObj != newObject) {
          logger.trace("Found modified object: {}", newObject.javaClass)

          events.add(makeEditEvent(newObject, oldObj))
        }
      }
    }

    return events
  }
}
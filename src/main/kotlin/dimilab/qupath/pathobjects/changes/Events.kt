package dimilab.qupath.pathobjects.changes

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dimilab.qupath.pathobjects.InstantTypeAdapter
import dimilab.qupath.pathobjects.changes.Event.Companion.gson
import dimilab.qupath.pathobjects.changes.Event.Companion.logger
import qupath.lib.io.GsonTools
import java.time.Instant
import java.util.*

sealed class Event {
  abstract val id: UUID
  abstract val timestamp: Instant
  abstract val eventType: String

  companion object {
    val logger = org.slf4j.LoggerFactory.getLogger(Event::class.java)
    val gson = GsonTools.getDefaultBuilder()
      .registerTypeAdapter(Instant::class.java, InstantTypeAdapter())
      .registerTypeAdapter(Event::class.java, EventTypeAdapter())
      .create()
  }
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

class EventTypeAdapter : com.google.gson.TypeAdapter<Event>() {
  override fun write(out: com.google.gson.stream.JsonWriter, value: Event?) {
    if (value == null) {
      out.nullValue()
      return
    }
    out.value(Event.gson.toJson(value))
  }

  override fun read(input: com.google.gson.stream.JsonReader): Event? {
    return try {
      val json: JsonObject = gson.fromJson(input, JsonObject::class.java)
      val eventType = json.get("eventType")
      if (eventType !is JsonPrimitive) {
        logger.error("Missing or invalid eventType in JSON: ${gson.toJson(json)}")
        return null
      }
      when (eventType.asString) {
        "create" -> gson.fromJson(json, CreateEvent::class.java)
        "edit" -> gson.fromJson(json, EditEvent::class.java)
        "delete" -> gson.fromJson(json, DeleteEvent::class.java)
        else -> {
          logger.error("Unknown eventType: ${json.get("eventType")}")
          null
        }
      }
    } catch (e: Exception) {
      logger.error("Failed to parse event line: $this", e)
      null
    }
  }
}
package dimilab.qupath.pathobjects.changes

import com.google.gson.JsonObject
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

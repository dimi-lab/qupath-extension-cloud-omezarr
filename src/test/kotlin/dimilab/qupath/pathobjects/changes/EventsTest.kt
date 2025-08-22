package dimilab.qupath.pathobjects.changes

import com.google.gson.JsonObject
import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EventsTest {
  @Test
  fun testDeserializeCreateEvent() {
    val id = UUID.randomUUID()
    val ts = Instant.now()
    val json = """
      {
        "id": "${id}",
        "timestamp": "${ts}",
        "eventType": "create",
        "fields": {
          "name": "new object",
          "geometry": { "type": "Point", "coordinates": [1.0, 2.0] }
        }
      }
    """.trimIndent()

    val event = Event.gson.fromJson(json, Event::class.java)
    assertIs<CreateEvent>(event)
    assertEquals(id, event.id)
    assertEquals(ts, event.timestamp)
    assertEquals("create", event.eventType)

    val fields: JsonObject = event.fields
    assertEquals("new object", fields.get("name").asString)
    assertEquals("Point", fields.getAsJsonObject("geometry").get("type").asString)
  }

  @Test
  fun testDeserializeEditEvent() {
    val id = UUID.randomUUID()
    val ts = Instant.now()
    val json = """
      {
        "id": "${id}",
        "timestamp": "${ts}",
        "eventType": "edit",
        "diff": {
          "properties": { "name": "updated" },
          "geometry": { "type": "Polygon" }
        }
      }
    """.trimIndent()

    val event = Event.gson.fromJson(json, Event::class.java)
    assertIs<EditEvent>(event)
    assertEquals(id, event.id)
    assertEquals(ts, event.timestamp)
    assertEquals("edit", event.eventType)

    val diff: JsonObject = event.diff
    assertEquals("updated", diff.getAsJsonObject("properties").get("name").asString)
    assertEquals("Polygon", diff.getAsJsonObject("geometry").get("type").asString)
  }

  @Test
  fun testDeserializeDeleteEvent() {
    val id = UUID.randomUUID()
    val ts = Instant.now()
    val json = """
      {
        "id": "${id}",
        "timestamp": "${ts}",
        "eventType": "delete"
      }
    """.trimIndent()

    val event = Event.gson.fromJson(json, Event::class.java)
    assertIs<DeleteEvent>(event)
    assertEquals(id, event.id)
    assertEquals(ts, event.timestamp)
    assertEquals("delete", event.eventType)
  }

  @Test
  fun testDeserializeUnknownEventTypeReturnsNull() {
    val id = UUID.randomUUID()
    val ts = Instant.now()
    val json = """
      {
        "id": "${id}",
        "timestamp": "${ts}",
        "eventType": "something-else",
        "fields": { "foo": "bar" }
      }
    """.trimIndent()

    val event: Event? = Event.gson.fromJson(json, Event::class.java)
    assertNull(event)
  }
}

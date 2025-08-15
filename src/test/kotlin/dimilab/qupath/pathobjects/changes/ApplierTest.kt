package dimilab.qupath.pathobjects.changes

import com.google.gson.JsonObject
import dimilab.qupath.pathobjects.Helpers.makeAnnotation
import dimilab.qupath.pathobjects.toJsonObject
import qupath.lib.common.ColorTools
import qupath.lib.io.GsonTools
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplierTest {
  private val gson = GsonTools.getInstance()

  @Test
  fun testApplyEvents() {
    val hierarchy: PathObjectHierarchy = PathObjectHierarchy()
    val po1 = makeAnnotation("po1")
    val po2 = makeAnnotation("po2")
    hierarchy.addObjects(listOf(po1, po2))

    val po3 = makeAnnotation("po3")

    val nameChange = "{\"properties\":{\"name\":\"po1_edited\"}}"
    val color321 = ColorTools.packRGB(3, 2, 1)
    val classificationChange = """
      {
        "properties": {
          "name": "po3_edited",
          "classification": {
            "name": "TestClass",
            "color": [1, 2, 3]
          }
        }
      }
    """.trimIndent()
    val colorChange = """
      {
        "properties": {
          "color": $color321
        }
      }
    """.trimIndent()

    val events = listOf(
      EditEvent(po1.id, diff = gson.fromJson(nameChange, JsonObject::class.java)),
      DeleteEvent(po2.id),
      CreateEvent(po3.id, fields = po3.toJsonObject()),
      EditEvent(po3.id, diff = gson.fromJson(classificationChange, JsonObject::class.java)),
      EditEvent(po3.id, diff = gson.fromJson(colorChange, JsonObject::class.java)),
    )

    var eventFired = false
    hierarchy.addListener { _ -> eventFired = true }

    Applier.applyEventsToHierarchy(events, hierarchy)
    assertTrue(eventFired)

    val newObjects = hierarchy.getAllObjects(false)
    assertEquals(2, newObjects.size)
    assertTrue(newObjects.any { it.id == po1.id && it.name == "po1_edited" })
    assertFalse(newObjects.any { it.id == po2.id })
    assertTrue(newObjects.any { it.id == po3.id && it.name == "po3_edited" })

    val po3new = newObjects.first { it.id == po3.id }
    assertEquals("po3_edited", po3new.name)
    assertEquals("TestClass", po3new.pathClass.name)
    assertEquals(ColorTools.packRGB(1, 2, 3), po3new.pathClass.color)
    assertEquals(color321, po3new.color)

    val deletions = listOf(po1.id, po2.id, po3.id).map {
      DeleteEvent(it)
    }
    eventFired = false
    Applier.applyEventsToHierarchy(deletions, hierarchy)
    assertTrue(eventFired)
    assertEquals(0, hierarchy.getAllObjects(false).size)
  }
}
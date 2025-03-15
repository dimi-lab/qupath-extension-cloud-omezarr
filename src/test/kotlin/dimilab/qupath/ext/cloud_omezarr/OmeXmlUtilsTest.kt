package dimilab.qupath.ext.cloud_omezarr

import kotlin.test.Test
import kotlin.test.assertEquals

class OmeXmlUtilsTest {
  private val testZarrRootUri = CloudOmeZarrServer::class.java.classLoader.getResource("test.zarr/")?.toURI()
    ?: throw IllegalStateException("Could not find test.zarr")

  @Test
  fun testOmeChannelsToQuPath() {
    val omeBaseUri = testZarrRootUri.resolve("OME/")
    val omeZarrMetadata = parseOmeXmlMetadata(omeBaseUri)
    val channels = omeChannelsToQuPath(omeZarrMetadata)

    assert(channels.size == 8)

    assertEquals("PDL1 (Opal 520)", channels[0].name)
    assertEquals(0xFFFF0000.toInt(), channels[0].color)
    assertEquals("CD8 (Opal 540)", channels[1].name)
    assertEquals(0xFFFFFF00.toInt(), channels[1].color)
    assertEquals("FoxP3 (Opal 570)", channels[2].name)
    assertEquals(0xFFFF8000.toInt(), channels[2].color)
    assertEquals("CD68 (Opal 620)", channels[3].name)
    assertEquals(0xFFFF00FF.toInt(), channels[3].color)
    assertEquals("PD1 (Opal 650)", channels[4].name)
    assertEquals(0xFF00FF00.toInt(), channels[4].color)
    assertEquals("CK (Opal 690)", channels[5].name)
    assertEquals(0xFF00FFFF.toInt(), channels[5].color)
    assertEquals("DAPI", channels[6].name)
    assertEquals(0xFF0000FF.toInt(), channels[6].color)
    assertEquals("Autofluorescence", channels[7].name)
    assertEquals(0xFF000000.toInt(), channels[7].color)
  }
}
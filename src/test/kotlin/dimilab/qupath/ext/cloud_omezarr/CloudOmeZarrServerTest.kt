package dimilab.qupath.ext.cloud_omezarr

import junit.framework.TestCase.assertEquals
import qupath.lib.images.servers.PixelType
import kotlin.math.roundToInt
import kotlin.test.Test

class CloudOmeZarrServerTest {
  val testZarrRootUri = CloudOmeZarrServer::class.java.classLoader.getResource("test.zarr/")?.toURI()
    ?: throw IllegalStateException("Could not find test.zarr")

  @Test
  fun testOmeXmlMetadata() {
    val server = CloudOmeZarrServer(
      zarrBaseUri = testZarrRootUri
    )

    assertEquals(1401, server.width)
    assertEquals(1050, server.height)

    assertEquals(4, server.nResolutions())
    assertEquals(1, server.getDownsampleForResolution(0).roundToInt())
    assertEquals(2, server.getDownsampleForResolution(1).roundToInt())
    assertEquals(4, server.getDownsampleForResolution(2).roundToInt())
    assertEquals(8, server.getDownsampleForResolution(3).roundToInt())

    assertEquals(8, server.nChannels())
    val names = listOf("PDL1 (Opal 520)", "CD8 (Opal 540)", "FoxP3 (Opal 570)", "CD68 (Opal 620)", "PD1 (Opal 650)", "CK (Opal 690)", "DAPI", "Autofluorescence")
    assertEquals(names, (0 until server.nChannels()).map { server.getChannel(it).name })

    assertEquals(PixelType.FLOAT32, server.pixelType)
  }

  @Test
  fun testReadTile() {
    val server = CloudOmeZarrServer(
      zarrBaseUri = testZarrRootUri
    )

    val result = server.readRegion(1.0, 0, 0, 100, 100)
    assertEquals(8, result.raster.numBands)
  }
}
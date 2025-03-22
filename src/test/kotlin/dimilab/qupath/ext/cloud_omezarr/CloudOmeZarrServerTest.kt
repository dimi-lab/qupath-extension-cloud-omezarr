package dimilab.qupath.ext.cloud_omezarr

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import io.mockk.every
import io.mockk.mockkStatic
import junit.framework.TestCase.assertEquals
import qupath.lib.images.servers.PixelType
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.io.path.toPath
import kotlin.math.roundToInt
import kotlin.test.Test

class CloudOmeZarrServerTest {
  private val testZarrRootUri = CloudOmeZarrServer::class.java.classLoader.getResource("test.zarr/")?.toURI()
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
    val names = listOf(
      "PDL1 (Opal 520)",
      "CD8 (Opal 540)",
      "FoxP3 (Opal 570)",
      "CD68 (Opal 620)",
      "PD1 (Opal 650)",
      "CK (Opal 690)",
      "DAPI",
      "Autofluorescence"
    )
    assertEquals(names, (0 until server.nChannels()).map { server.getChannel(it).name })

    assertEquals(PixelType.FLOAT32, server.pixelType)
  }

  @Test
  fun testGcsUri() {
    val fakeStorageOptions = LocalStorageHelper.getOptions()
    val fakeStorage = fakeStorageOptions.service
    // Forward every file in the testZarrRootUri folders to the fakeStorage
    val testRoot = testZarrRootUri.toPath()
    Files.find(testRoot, 10, { path, _ -> !path.isDirectory() })
      .forEach { path ->
        val relativePath = path.relativeTo(testRoot)
        val targetBlob = BlobId.of("test-bucket", "test-image.zarr/${relativePath.toString()}")
        println("Storing $relativePath as $targetBlob")
        val blobInfo = BlobInfo.newBuilder(targetBlob).build()
        fakeStorage.create(blobInfo, Files.readAllBytes(path))
      }

    mockkStatic(StorageOptions::getDefaultInstance)
    every { StorageOptions.getDefaultInstance() } returns fakeStorageOptions

    val server = CloudOmeZarrServer(
      zarrBaseUri = URI.create("gs://test-bucket/test-image.zarr/")
    )

    assertEquals(1401, server.width)
    assertEquals(1050, server.height)

    val result = server.readRegion(1.0, 0, 0, 101, 102)
    assertEquals(101, result.width)
    assertEquals(102, result.height)
    assertEquals(8, result.raster.numBands)
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
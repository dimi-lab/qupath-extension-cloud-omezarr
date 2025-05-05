package dimilab.qupath.ext.omezarr

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import io.mockk.every
import io.mockk.mockkStatic
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.io.path.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CloudOmeZarrServerBuilderTest {

  @Test
  fun testPathRecognition() {
    val builder = CloudOmeZarrServerBuilder()

    // Test with a non-munged URI
    builder.checkImageSupport(URI.create("gs://a-bucket/a-folder/test.zarr")).let {
      assertNotNull(it)
      assertEquals(5f, it.supportLevel)
      assertEquals("gs://a-bucket/a-folder/test.zarr", it.builders.first().urIs.first().toString())
    }

    // Test with a munged URI at root
    builder.checkImageSupport(URI.create("file:/gs:/a-bucket/a-folder/test.zarr")).let {
      assertNotNull(it)
      assertEquals(5f, it.supportLevel)
      assertEquals("gs://a-bucket/a-folder/test.zarr", it.builders.first().urIs.first().toString())
    }

    // Test with a munged URI w/ path
    builder.checkImageSupport(URI.create("file:/a-folder/gs:/a-bucket/a-folder/test.zarr")).let {
      assertNotNull(it)
      assertEquals(5f, it.supportLevel)
      assertEquals("gs://a-bucket/a-folder/test.zarr", it.builders.first().urIs.first().toString())
    }

    // Test with a Windows munged URI
    val windowsFile = File("C:\\some-folder\\gs:/a-bucket/a-folder/test.zarr")
    builder.checkImageSupport(windowsFile.toURI()).let {
      assertNotNull(it)
      assertEquals(5f, it.supportLevel)
      assertEquals("gs://a-bucket/a-folder/test.zarr", it.builders.first().urIs.first().toString())
    }
  }
}
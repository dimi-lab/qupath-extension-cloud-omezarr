package dimilab.omezarr

import com.google.cloud.storage.contrib.nio.CloudStorageFileSystem
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OmeZarrUtilsTest {
  @Test
  fun getZarrRoot_file() {
    val uri = URI.create("file:///folder/animage.zarr/")
    val zarrRoot = getZarrRoot(uri)
    assertEquals("file", zarrRoot.fileSystem.provider().scheme)
    assertEquals("/folder/animage.zarr", zarrRoot.toString())
  }

  @Test
  fun getZarrRoot_gs() {
    val uri = URI.create("gs://a-bucket/folder/animage.zarr/")
    val zarrRoot = getZarrRoot(uri)
    assertEquals("gs", zarrRoot.fileSystem.provider().scheme)
    assertIs<CloudStorageFileSystem>(zarrRoot.fileSystem)
    assertEquals("a-bucket", (zarrRoot.fileSystem as CloudStorageFileSystem).bucket())
    assertEquals("/folder/animage.zarr/", zarrRoot.toString())
  }

  @Test
  fun getZarrRoot_gs_underscore() {
    // Bucket names with underscores need special treatment…
    val uri = URI.create("gs://a_bucket/folder/animage.zarr/")
    val zarrRoot = getZarrRoot(uri)
    assertEquals("gs", zarrRoot.fileSystem.provider().scheme)
    assertIs<CloudStorageFileSystem>(zarrRoot.fileSystem)
    assertEquals("a_bucket", (zarrRoot.fileSystem as CloudStorageFileSystem).bucket())
    assertEquals("/folder/animage.zarr/", zarrRoot.toString())
  }

  @Test
  fun getZarrRoot_unsupported() {
    val uri = URI.create("s3://a-bucket/folder/animage.zarr/")
    val exception = runCatching { getZarrRoot(uri) }.exceptionOrNull()
    assertIs<IllegalArgumentException>(exception)
    assertEquals("Unsupported scheme 's3'", exception?.message)
  }

  @Test
  fun getZarrRoot_zattrs() {
    val uri = URI.create("gs://a-bucket/folder/animage.zarr/.zattrs")
    val zarrRoot = getZarrRoot(uri)
    assertEquals("/folder/animage.zarr/", zarrRoot.toString())
  }
}
package dimilab.qupath.ext.omezarr

import com.bc.zarr.ZarrConstants.*
import com.bc.zarr.storage.Store
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.*
import java.util.stream.Stream

class CloudZarrStore(val backingStore: Store) : Store {
  companion object {
    private val logger = org.slf4j.LoggerFactory.getLogger(CloudZarrStore::class.java)

    fun fromPath(zarrRoot: Path): CloudZarrStore {
      val backingStore = com.bc.zarr.storage.FileSystemStore(zarrRoot)
      return CloudZarrStore(backingStore)
    }
  }

  val cachedAttributes = mutableMapOf<String, ByteArray>()

  override fun getInputStream(key: String?): InputStream? {
    if (cachedAttributes.containsKey(key)) {
      logger.debug("Cached read for key {}", key)
      return ByteArrayInputStream(cachedAttributes[key])
    }

    logger.debug("Uncached read for key {}", key)
    return backingStore.getInputStream(key)
  }

  private val cachedExtensions = listOf(
    FILENAME_DOT_ZATTRS,
    FILENAME_DOT_ZARRAY,
    FILENAME_DOT_ZGROUP,
  )

  private fun isAttribute(key: String?): Boolean {
    return cachedExtensions.any {
      key?.endsWith(it) ?: false
    }
  }

  override fun getOutputStream(key: String?): OutputStream? {
    throw NotImplementedError("Zarrs are read-only")
  }

  override fun delete(key: String?) {
    throw NotImplementedError("Zarrs are read-only")
  }

  override fun getArrayKeys(): TreeSet<String?>? {
    TODO("Do we need this?")
  }

  override fun getGroupKeys(): TreeSet<String?>? {
    TODO("Do we need this?")
  }

  override fun getKeysEndingWith(suffix: String?): TreeSet<String?>? {
    TODO("Do we need this?")
  }

  override fun getRelativeLeafKeys(key: String?): Stream<String?>? {
    TODO("Do we need this?")
  }
}
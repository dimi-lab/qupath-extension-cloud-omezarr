package dimilab.omezarr

import com.bc.zarr.ZarrConstants
import com.bc.zarr.storage.FileSystemStore
import com.bc.zarr.storage.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.TreeSet
import java.util.stream.Stream

class CloudZarrStore(val backingStore: Store) : Store {
  companion object {
    private val logger = LoggerFactory.getLogger(CloudZarrStore::class.java)

    private val cachedZarrs = mutableMapOf<Path, CloudZarrStore>()

    fun fromPath(zarrRoot: Path): CloudZarrStore {
      synchronized(cachedZarrs) {
        val existing = cachedZarrs[zarrRoot]
        if (existing != null) {
          logger.debug("Using cached CloudZarrStore for path {}", zarrRoot)
          return existing
        }

        logger.debug("Creating new CloudZarrStore for path {}", zarrRoot)
        val backingStore = FileSystemStore(zarrRoot)
        val zarr = CloudZarrStore(backingStore)
        cachedZarrs[zarrRoot] = zarr
        return zarr
      }
    }
  }

  val cachedAttributes = mutableMapOf<String, ByteArray>()
  val deferredFetches = mutableMapOf<String, Deferred<ByteArray>>()

  override fun getInputStream(key: String?): InputStream? {
    if (key == null) {
      return null
    }

    if (cachedAttributes.containsKey(key)) {
      logger.debug("Cached read for key {}", key)
      return ByteArrayInputStream(cachedAttributes[key])
    }

    val result = getOrWaitForDeferred(key)
    return ByteArrayInputStream(result)
  }

  private fun getOrWaitForDeferred(key: String): ByteArray {
    return runBlocking {
      val deferredByteArray = synchronized(deferredFetches) {
        val existing = deferredFetches[key]
        if (existing != null) {
          logger.debug("Waiting for existing fetch for key {}", key)
          return@synchronized existing
        }

        logger.debug("Starting new fetch for key {}", key)
        val deferred = makeDeferred(key)
        deferredFetches[key] = deferred
        deferred
      }

      deferredByteArray.await()
    }
  }

  private fun CoroutineScope.makeDeferred(key: String): Deferred<ByteArray> = async {
    logger.debug("Reading key {} from backing store", key)
    backingStore.getInputStream(key).use { input ->
      val byteArray = input.readBytes()

      // Store cacheable attributes.
      if (isAttribute(key)) {
        synchronized(cachedAttributes) {
          cachedAttributes[key] = byteArray
        }
      }

      // Waiters were notified– now remove the deferred fetch
      synchronized(deferredFetches) {
        deferredFetches.remove(key)
      }

      byteArray
    }
  }

  private val cachedExtensions = listOf(
    ZarrConstants.FILENAME_DOT_ZATTRS,
    ZarrConstants.FILENAME_DOT_ZARRAY,
    ZarrConstants.FILENAME_DOT_ZGROUP,
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
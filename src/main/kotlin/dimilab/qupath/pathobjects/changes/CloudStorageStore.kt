package dimilab.qupath.pathobjects.changes

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import dimilab.qupath.ext.omezarr.gsUri
import dimilab.qupath.ext.omezarr.toBlobId
import dimilab.qupath.pathobjects.changes.Event.Companion.gson
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

interface StoreListener {
  fun onNewEvents(events: List<Event>)
}

class CloudStorageStore {
  companion object {
    private val logger = LoggerFactory.getLogger(CloudStorageStore::class.java)
  }

  private val gcsService: Storage
  private val ioDispatcher: CoroutineDispatcher
  private val bucket: String
  private val prefix: String

  private val listeners = CopyOnWriteArrayList<StoreListener>()

  private val currentSyncLock = ReentrantLock()
  private var currentSync: Deferred<Unit>? = null
  private val writeQueue = mutableListOf<Event>()

  private val lastSeenChangesetLock = ReentrantLock()
  private var lastSeenChangesetId = 0

  constructor(
    rootUri: URI,
    storage: Storage = StorageOptions.getDefaultInstance().service,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(
    rootUri.toBlobId(), storage, ioDispatcher
  )

  constructor(
    rootBlobId: BlobId,
    storage: Storage = StorageOptions.getDefaultInstance().service,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) {
    this.gcsService = storage
    this.bucket = rootBlobId.bucket
    this.prefix = ensureDirPrefix(rootBlobId.name)
    this.ioDispatcher = ioDispatcher
  }

  fun syncEvents(blocking: Boolean) {
    val deferred = currentSyncLock.withLock {
      if (currentSync != null) {
        logger.info("Sync already in progress, waiting for completion")
        currentSync!!
      } else {
        logger.info("Launching new sync")
        CoroutineScope(ioDispatcher).async {
          try {
            syncThreadMain()
          } finally {
            currentSyncLock.withLock {
              currentSync = null
            }
          }
        }.also { d ->
          currentSync = d
        }
      }
    }

    if (blocking) {
      runBlocking {
        deferred.await()
      }
    }
  }

  private fun syncThreadMain() {
    val changesets = indexChangesetBlobs().filter { (idx, _) -> idx > lastSeenChangesetId }
    if (changesets.isEmpty()) {
      logger.info("Sync completed, no new changesets found")
      return
    }

    logger.info(
      "Reading {} changeset IDs: {}", changesets.size, changesets.keys.sorted().joinToString(", ")
    )
    val indexedEvents = readChangesets(changesets)

    val events = lastSeenChangesetLock.withLock {
      indexedEvents.flatMap { (idx, events) ->
        if (idx <= lastSeenChangesetId) {
          logger.warn("Changeset ID {} is not newer than last seen ID {}", idx, lastSeenChangesetId)
        }
        events
      }.also { _ ->
        lastSeenChangesetId = max(lastSeenChangesetId, changesets.keys.max())
      }
    }

    logger.info("Sync completed, {} new events found", events.size)

    if (events.isNotEmpty()) {
      // Note: no need for synchronization here, due to CopyOnWriteArrayList
      listeners.forEach { it.onNewEvents(events) }
    }
  }

  fun addListener(listener: StoreListener) {
    // Note: no need for synchronization here, due to CopyOnWriteArrayList
    listeners.addIfAbsent(listener)
  }

  fun removeListener(listener: StoreListener) {
    // Note: no need for synchronization here, due to CopyOnWriteArrayList
    listeners.remove(listener)
  }

  fun storeEvents(events: List<Event>) {
    if (events.isEmpty()) return

    synchronized(writeQueue) {
      writeQueue.addAll(events)
      logger.info("Enqueued {} change events for storage", events.size)
    }

    logger.info("Launching event write thread")
    CoroutineScope(ioDispatcher).launch {
      eventsWriteThread()
    }
  }

  private fun eventsWriteThread() {
    // FIXME?: if we fail, we have to put these back onto the queue
    val events = synchronized(writeQueue) {
      writeQueue.toList().also {
        writeQueue.clear()
      }
    }

    if (events.isEmpty()) {
      logger.debug("No events to write, exiting write thread")
      return
    }

    logger.info("Writing {} events to cloud storage", events.size)

    // Compose JSONL content
    val content = buildString {
      events.forEach { append(gson.toJson(it)).append('\n') }
    }.toByteArray(StandardCharsets.UTF_8)

    // Determine the next available changelist file name and write with precondition
    // Optimistically assume it's the next one we know about, sync & retry if not.
    while (true) {
      val wroteIndex = attemptSynchronizedWrite(content)

      if (wroteIndex != null) {
        logger.info("Successfully wrote {} events", events.size)
        return
      }

      logger.debug("Syncing & retrying")
      syncEvents(true)
    }
  }

  private fun attemptSynchronizedWrite(content: ByteArray): Int? {
    // This locks the entire store for the duration of the write.

    lastSeenChangesetLock.withLock {
      val attemptIndex = lastSeenChangesetId + 1
      val blobName = prefix + changesetFilename(attemptIndex)
      val blobInfo = BlobInfo.newBuilder(bucket, blobName).build()

      try {
        logger.debug("Attempting to write changeset to: {}", blobInfo.gsUri)
        gcsService.create(blobInfo, content, Storage.BlobTargetOption.doesNotExist())
        lastSeenChangesetId = attemptIndex
        logger.info("Wrote changeset to location: {}", blobInfo.gsUri)
        return attemptIndex
      } catch (ex: com.google.cloud.storage.StorageException) {
        if (ex.code != 412) {
          throw ex
        }

        logger.debug("Changeset already exists: {}", blobInfo.gsUri)
        return null
      }
    }
  }

  private fun indexChangesetBlobs(): Map<Int, BlobId> {
    val blobs = mutableMapOf<Int, BlobId>()
    val regex = Regex("^" + Regex.escape(prefix) + "events_(\\d+)\\.jsonl$")

    var page = gcsService.list(
      bucket, Storage.BlobListOption.prefix(prefix), Storage.BlobListOption.currentDirectory()
    )
    while (true) {
      for (blob in page.iterateAll()) {
        val name = blob.name
        if (!name.endsWith(".jsonl")) continue
        val match = regex.matchEntire(name)
        if (match != null) {
          val idx = match.groupValues[1].toInt()
          blobs[idx] = blob.blobId
        }
      }
      if (!page.hasNextPage()) break
      page = page.nextPage
    }

    return blobs
  }

  private fun readChangesets(changesets: Map<Int, BlobId>): Map<Int, Sequence<Event>> {
    val loadedEvents = changesets.mapValues { (idx, changeset) ->
      logger.debug("Loading changeset {} from {}", idx, changeset.gsUri)
      val jsonl = gcsService.readAllBytes(changeset).toString(StandardCharsets.UTF_8)
      jsonl.lineSequence().mapNotNull { line ->
        gson.fromJson(line, Event::class.java)
      }
    }

    return loadedEvents
  }

  private fun ensureDirPrefix(name: String): String {
    return when {
      name.isEmpty() -> ""
      name.endsWith('/') -> name
      else -> "$name/"
    }
  }

  private fun changesetFilename(index: Int): String = String.format("events_%010d.jsonl", index)

  fun changesetRoot(): BlobId {
    return BlobId.of(bucket, prefix)
  }
}
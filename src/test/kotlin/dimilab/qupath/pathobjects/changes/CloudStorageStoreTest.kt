package dimilab.qupath.pathobjects.changes

import com.google.api.gax.paging.Page
import com.google.cloud.storage.*
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CloudStorageStoreTest {

  // Manual dispatcher to deterministically execute enqueued coroutine tasks
  private class ManualDispatcher : CoroutineDispatcher() {
    private val queue: BlockingQueue<Runnable> = LinkedBlockingQueue()

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
      queue.add(block)
    }

    fun runNext(): Boolean {
      val task = queue.poll() ?: return false
      task.run()
      return true
    }
  }

  private fun makeStorageWithBacking(
    bucket: String, backing: ConcurrentMap<String, ByteArray>,
    onList: ((prefix: String?) -> Unit)? = null,
    onCreate: ((name: String) -> Unit)? = null,
  ): Storage {
    val storage = mockk<Storage>()

    // list: ignore options content; just return all objects in backing; call onList hook
    every { storage.list(any<String>(), any<Storage.BlobListOption>(), any<Storage.BlobListOption>()) } answers {
      onList?.invoke(null)
      val blobs = backing.keys.toList().map { name: String ->
        val blob = mockk<Blob>()
        every { blob.name } returns name
        every { blob.blobId } returns BlobId.of(bucket, name)
        blob
      }
      object : Page<Blob> {
        override fun getValues(): MutableIterable<Blob> = blobs.toMutableList()
        override fun iterateAll(): MutableIterable<Blob> = blobs.toMutableList()
        override fun getNextPage(): Page<Blob>? = null
        override fun hasNextPage(): Boolean = false
        override fun getNextPageToken(): String? = null
      }
    }

    // readAllBytes
    every { storage.readAllBytes(any<BlobId>()) } answers {
      val id = firstArg<BlobId>()
      backing[id.name] ?: ByteArray(0)
    }

    // create with doesNotExist precondition
    every { storage.create(any<BlobInfo>(), any<ByteArray>(), eq(Storage.BlobTargetOption.doesNotExist())) } answers {
      val info = firstArg<BlobInfo>()
      val content = secondArg<ByteArray>()
      val name = info.name
      synchronized(backing) {
        if (backing.containsKey(name)) {
          throw StorageException(412, "Precondition Failed")
        }
        backing[name] = content
      }
      onCreate?.invoke(name)
      mockk<Blob>(relaxed = true)
    }

    return storage
  }

  @Test
  fun sync_concurrentJoinAndSeparateRuns() {
    val bucket = "test-bucket"
    val prefix = "changes/"
    val uri = URI.create("gs://$bucket/$prefix")

    val objects = ConcurrentHashMap<String, ByteArray>()

    val listCalls = AtomicInteger(0)
    val listEnter = CountDownLatch(1)
    val releaseList = CountDownLatch(1)

    val storage = mockk<Storage>()
    every { storage.list(any<String>(), any<Storage.BlobListOption>(), any<Storage.BlobListOption>()) } answers {
      listCalls.incrementAndGet()
      listEnter.countDown()
      // block first call until released; subsequent calls proceed immediately
      if (releaseList.count == 1L) {
        releaseList.await(2, TimeUnit.SECONDS)
      }
      val blobs = objects.keys.toList().map { name: String ->
        val blob = mockk<Blob>()
        every { blob.name } returns name
        every { blob.blobId } returns BlobId.of(bucket, name)
        blob
      }
      object : Page<Blob> {
        override fun getValues(): MutableIterable<Blob> = blobs.toMutableList()
        override fun iterateAll(): MutableIterable<Blob> = blobs.toMutableList()
        override fun getNextPage(): Page<Blob>? = null
        override fun hasNextPage(): Boolean = false
        override fun getNextPageToken(): String? = null
      }
    }
    every { storage.readAllBytes(any<BlobId>()) } answers { ByteArray(0) }
    every {
      storage.create(
        any<BlobInfo>(),
        any<ByteArray>(),
        eq(Storage.BlobTargetOption.doesNotExist())
      )
    } answers { mockk<Blob>(relaxed = true) }

    val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    val store = CloudStorageStore(uri, storage, dispatcher)

    // Launch first sync non-blocking so it runs in background and blocks inside list
    store.syncEvents(false)

    // Ensure first list started and is blocking
    assertTrue(listEnter.await(1, TimeUnit.SECONDS))

    // Now call sync again while first is in progress; it should join and not trigger another list immediately
    val joinFinished = CountDownLatch(1)
    thread {
      store.syncEvents(true)
      joinFinished.countDown()
    }

    // Give a little time; list should have been called only once so far
    Thread.sleep(100)
    assertEquals(1, listCalls.get(), "Second sync should have joined the first one")

    // Release the first list to complete sync
    releaseList.countDown()

    // Wait for the joiner to complete
    assertTrue(joinFinished.await(1, TimeUnit.SECONDS))

    // Now, trigger two separate syncs at different times; each should call list
    store.syncEvents(true)
    store.syncEvents(true)
    // Total list calls: 1 (first joined) + 2 (separate) = 3
    assertEquals(3, listCalls.get())
  }

  @Test
  fun write_concurrentCombinedVsSeparate() {
    val bucket = "test-bucket"
    val prefix = "changes/"
    val uri = URI.create("gs://$bucket/$prefix")

    val objects = ConcurrentHashMap<String, ByteArray>()

    // Combined case using ManualDispatcher
    run {
      val manual = ManualDispatcher()
      val created = CopyOnWriteArrayList<String>()
      val storage = makeStorageWithBacking(bucket, objects, onCreate = { created.add(it) })
      val store = CloudStorageStore(uri, storage, manual)

      val e1 = CreateEvent(UUID.randomUUID(), fields = JsonObject())
      val e2 = CreateEvent(UUID.randomUUID(), fields = JsonObject())

      store.storeEvents(listOf(e1))
      store.storeEvents(listOf(e2))

      // Run first write thread: it should drain both events and write a single blob
      manual.runNext()
      // Run second write thread: it should find queue empty and do nothing
      manual.runNext()

      val createdOnce = created.filter { it.startsWith(prefix) }
      assertEquals(1, createdOnce.size, "Combined write should create exactly one changeset")
      assertTrue(createdOnce[0].endsWith("events_0000000001.jsonl"))
      val content = objects[createdOnce[0]]!!.toString(StandardCharsets.UTF_8)
      assertEquals(2, content.trim().lines().size)

      // cleanup for next sub-test
      objects.clear()
      created.clear()
    }

    // Separate case using ManualDispatcher
    run {
      val manual = ManualDispatcher()
      val created = CopyOnWriteArrayList<String>()
      val storage = makeStorageWithBacking(bucket, objects, onCreate = { created.add(it) })
      val store = CloudStorageStore(uri, storage, manual)

      val e1 = CreateEvent(UUID.randomUUID(), fields = JsonObject())
      val e2 = CreateEvent(UUID.randomUUID(), fields = JsonObject())

      store.storeEvents(listOf(e1))
      // Run first write before enqueuing the second
      manual.runNext()

      store.storeEvents(listOf(e2))
      manual.runNext()

      val createdOnce = created.filter { it.startsWith(prefix) }.sorted()
      assertEquals(2, createdOnce.size, "Separate writes should create two changesets")
      assertTrue(createdOnce[0].endsWith("events_0000000001.jsonl"))
      assertTrue(createdOnce[1].endsWith("events_0000000002.jsonl"))
    }
  }

  @Test
  fun write_SyncAndRetrySuccess() {
    val bucket = "test-bucket"
    val prefix = "changes/"
    val uri = URI.create("gs://$bucket/$prefix")

    val objects = ConcurrentHashMap<String, ByteArray>()
    // Preload index 1 to force 412 on first write attempt
    objects["${prefix}events_0000000001.jsonl"] = "".toByteArray(StandardCharsets.UTF_8)

    val created = CopyOnWriteArrayList<String>()

    val storage = makeStorageWithBacking(bucket, objects, onCreate = { created.add(it) })

    val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    val store = CloudStorageStore(uri, storage, dispatcher)

    val e1 = CreateEvent(UUID.randomUUID(), fields = JsonObject())

    val done = CountDownLatch(1)

    // Wrap storage.create to signal completion on success
    // We need to re-stub create to add latch signal while delegating to backing
    // Using MockK 'every' again on the same mock to override behavior
    every { storage.create(any<BlobInfo>(), any<ByteArray>(), eq(Storage.BlobTargetOption.doesNotExist())) } answers {
      val info = firstArg<BlobInfo>()
      val content = secondArg<ByteArray>()
      val name = info.name
      synchronized(objects) {
        if (objects.containsKey(name)) {
          throw StorageException(412, "Precondition Failed")
        }
        objects[name] = content
      }
      created.add(name)
      done.countDown()
      mockk<Blob>(relaxed = true)
    }

    store.storeEvents(listOf(e1))

    // Wait for success
    assertTrue(done.await(2, TimeUnit.SECONDS), "Write did not complete in time")

    // Make sure we wrote the right objects into the 2nd file
    val createdNames = created.filter { it.startsWith(prefix) }
    assertEquals(1, createdNames.size)
    assertTrue(createdNames[0].endsWith("events_0000000002.jsonl"), "Write after sync should use next index")
    val storedObjects = objects[createdNames[0]]!!.toString(StandardCharsets.UTF_8).trim().lines().map { Event.gson.fromJson(it, Event::class.java) }
    assertEquals(listOf(e1), storedObjects)
  }
}

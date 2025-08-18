package dimilab.omezarr

import com.bc.zarr.storage.Store
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CloudZarrStoreTest {

  @Test
  fun `test fromPath returns cached instance`() {
    val path = mockk<Path>()
    val store1 = CloudZarrStore.fromPath(path)
    val store2 = CloudZarrStore.fromPath(path)

    // Verify that the same instance is returned
    assertSame(store1, store2)
  }

  @Test
  fun `test multiple threads accessing same CloudZarrStore`() {
    // Create a mock backing store that will return a controlled response
    val backingStore = mockk<Store>()
    val testKey = "test.zattrs"
    val testData = "test data".toByteArray()

    // Create a controlled input stream that will only return when signaled
    val inputStream = ByteArrayInputStream(testData)
    every { backingStore.getInputStream(testKey) } returns inputStream

    // Create a spy on CloudZarrStore to track calls to makeDeferred
    val cloudZarrStore = spyk(CloudZarrStore(backingStore), recordPrivateCalls = true)

    // Track the number of deferred threads created
    val deferredThreadsCreated = AtomicInteger(0)

    // Control when the first deferred thread completes
    val firstThreadStarted = CountDownLatch(1)
    val secondThreadStarted = CountDownLatch(1)
    val allowFirstThreadToComplete = CountDownLatch(1)

    // Track the order of events
    val events = mutableListOf<String>()
    val eventsLock = Any()

    // Replace makeDeferred with our controlled version
    val originalMakeDeferred = cloudZarrStore::class.java.getDeclaredMethod(
      "makeDeferred",
      CoroutineScope::class.java,
      String::class.java
    )
    originalMakeDeferred.isAccessible = true

    every {
      cloudZarrStore["makeDeferred"](any<CoroutineScope>(), testKey)
    } answers {
      val scope = arg<CoroutineScope>(0)

      // Increment counter for deferred threads created
      deferredThreadsCreated.incrementAndGet()

      scope.async {
        synchronized(eventsLock) { events.add("Deferred thread started") }

        // Signal that the first thread has started
        firstThreadStarted.countDown()

        // Wait for signal to complete
        allowFirstThreadToComplete.await()

        // Now read from the backing store
        backingStore.getInputStream(testKey).use { input ->
          val byteArray = input.readBytes()

          // Store cacheable attributes
          synchronized(cloudZarrStore.cachedAttributes) {
            cloudZarrStore.cachedAttributes[testKey] = byteArray
          }

          // Remove the deferred fetch
          synchronized(cloudZarrStore.deferredFetches) {
            cloudZarrStore.deferredFetches.remove(testKey)
          }

          synchronized(eventsLock) { events.add("Deferred thread completed") }
          byteArray
        }
      }
    }

    // Create two threads to access the CloudZarrStore
    val executor = Executors.newFixedThreadPool(2)

    // First thread
    val firstThreadResult = AtomicReference<ByteArray>()
    val firstThreadTask = executor.submit {
      synchronized(eventsLock) { events.add("First thread started") }
      val result = runBlocking {
        val inputStream = cloudZarrStore.getInputStream(testKey)
        inputStream?.readBytes()
      }
      firstThreadResult.set(result)
      synchronized(eventsLock) { events.add("First thread completed") }
    }

    // Wait for the first thread to start its deferred operation
    firstThreadStarted.await(5, TimeUnit.SECONDS)

    // Second thread
    val secondThreadResult = AtomicReference<ByteArray>()
    val secondThreadTask = executor.submit {
      synchronized(eventsLock) { events.add("Second thread started") }
      secondThreadStarted.countDown()

      val result = runBlocking {
        val inputStream = cloudZarrStore.getInputStream(testKey)
        inputStream?.readBytes()
      }
      secondThreadResult.set(result)
      synchronized(eventsLock) { events.add("Second thread completed") }
    }

    // Wait for the second thread to start
    secondThreadStarted.await(5, TimeUnit.SECONDS)

    // Give some time for the second thread to potentially create its own deferred thread
    // (which it shouldn't do)
    Thread.sleep(500)

    // Now allow the first deferred thread to complete
    allowFirstThreadToComplete.countDown()

    // Wait for both threads to complete
    firstThreadTask.get(5, TimeUnit.SECONDS)
    secondThreadTask.get(5, TimeUnit.SECONDS)

    // Shutdown the executor
    executor.shutdown()
    executor.awaitTermination(5, TimeUnit.SECONDS)

    // Verify that only one deferred thread was created
    assertEquals(1, deferredThreadsCreated.get(), "Only one deferred thread should be created")

    // Verify that both threads got the same result
    assertContentEquals(
      firstThreadResult.get(), secondThreadResult.get(),
      "Both threads should get the same content"
    )

    // Print the events to verify the order
    synchronized(eventsLock) {
      println("Events order:")
      events.forEachIndexed { index, event -> println("$index: $event") }

      // Verify the order of events
      val firstThreadStartedIndex = events.indexOf("First thread started")
      val secondThreadStartedIndex = events.indexOf("Second thread started")
      val deferredThreadCompletedIndex = events.indexOf("Deferred thread completed")
      val firstThreadCompletedIndex = events.indexOf("First thread completed")
      val secondThreadCompletedIndex = events.indexOf("Second thread completed")

      // Verify that the deferred thread completed after the second thread started
      assertTrue(
        secondThreadStartedIndex < deferredThreadCompletedIndex,
        "Deferred thread should complete after the second thread starts"
      )

      // Verify that both threads completed after the deferred thread completed
      assertTrue(
        deferredThreadCompletedIndex < firstThreadCompletedIndex,
        "First thread should complete after the deferred thread completes"
      )
      assertTrue(
        deferredThreadCompletedIndex < secondThreadCompletedIndex,
        "Second thread should complete after the deferred thread completes"
      )
    }
  }
}
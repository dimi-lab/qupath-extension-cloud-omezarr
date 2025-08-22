package dimilab.qupath.ext.omezarr

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import com.google.cloud.storage.transfermanager.ParallelDownloadConfig
import com.google.cloud.storage.transfermanager.TransferManagerConfig
import com.google.cloud.storage.transfermanager.TransferStatus
import dimilab.qupath.ext.omezarr.CloudStorageUtils.Companion.logger
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class CloudStorageUtils {
  companion object {
    val logger: Logger = org.slf4j.LoggerFactory.getLogger(CloudStorageUtils::class.java)
  }
}

fun downloadUrisToTemp(uris: List<URI>): Map<URI, Path> {
  val remoteBlobs = uris.map { uri ->
    if (uri.scheme != "gs") {
      throw IllegalArgumentException("Unsupported scheme '${uri.scheme}'")
    }
    BlobInfo.newBuilder(uri.toBlobId()).build()
  }

  val bucket = remoteBlobs.first().bucket

  if (remoteBlobs.any { it.bucket != bucket }) {
    throw IllegalArgumentException("All URIs must be in the same bucket")
  }

  val getRoot = { b: BlobInfo -> b.name.substringBeforeLast("/") + "/" }

  val remoteRoot = getRoot(remoteBlobs.first())
  if (remoteBlobs.any { getRoot(it) != remoteRoot }) {
    throw IllegalArgumentException("All URIs must share the same prefix directories")
  }

  val localRoot = Files.createTempDirectory("qupath-cloudomezarr")
  Runtime.getRuntime().addShutdownHook(Thread { FileUtils.forceDelete(localRoot.toFile()) })

  logger.info("Downloading {} blobs to {}: {}", remoteBlobs.size, localRoot, remoteBlobs)

  val transferManager = TransferManagerConfig
    .newBuilder()
    .setAllowDivideAndConquerDownload(true)
    .build()
    .service

  val results = transferManager.use {
    val downloadConfig = ParallelDownloadConfig
      .newBuilder()
      .setBucketName(bucket)
      .setDownloadDirectory(localRoot)
      .setStripPrefix(remoteRoot)
      .build()
    it.downloadBlobs(remoteBlobs, downloadConfig).downloadResults
  }

  results.forEach {
    require(it.status == TransferStatus.SUCCESS) {
      "Failed to download blob ${it.input.name} with status ${it.exception}"
    }
  }

  return uris.associateWith {
    val filename = File(it.path).name
    val localPath = localRoot.resolve(filename)
    localPath.exists() || throw IOException("Expected remote path $it to be at local path $localPath")

    localPath
  }
}

fun uploadToStorage(path: Path, target: BlobId) {
  val blobInfo = BlobInfo.newBuilder(target.bucket, target.name).build()

  val service = StorageOptions.newBuilder().build().service
  service.createFrom(blobInfo, path)
}

// Extension function to convert a gs:// URI to a BlobId
fun URI.toBlobId(): BlobId {
  return this.toString().toBlobId()
}

// Extension function to convert a string like gs://bucket/path to a BlobId
fun String.toBlobId(): BlobId {
  return BlobId.fromGsUtilUri(this)
}

inline val BlobInfo.gsUri: String
  get() = this.blobId.toGsUtilUri()

inline val BlobId.gsUri: String
  get() = this.toGsUtilUri()
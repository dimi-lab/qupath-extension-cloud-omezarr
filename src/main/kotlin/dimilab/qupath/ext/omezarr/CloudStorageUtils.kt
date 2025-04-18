package dimilab.qupath.ext.omezarr

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
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
  val transferManager = TransferManagerConfig
    .newBuilder()
    .setAllowDivideAndConquerDownload(true)
    .build()
    .service

  val remoteBlobs = uris.map { uri ->
    if (uri.scheme != "gs") {
      throw IllegalArgumentException("Unsupported scheme '${uri.scheme}'")
    }
    val blobId = BlobId.fromGsUtilUri(uri.toString())
    BlobInfo.newBuilder(blobId).build()
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
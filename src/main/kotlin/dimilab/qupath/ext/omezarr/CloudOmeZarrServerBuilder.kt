package dimilab.qupath.ext.omezarr

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.cloud.storage.StorageException
import qupath.lib.images.servers.ImageServer
import qupath.lib.images.servers.ImageServerBuilder
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder
import qupath.lib.images.servers.ImageServerBuilder.UriImageSupport
import qupath.lib.images.servers.ImageServerMetadata
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URI

class CloudOmeZarrServerBuilder : ImageServerBuilder<BufferedImage> {
  companion object {
    private val logger = org.slf4j.LoggerFactory.getLogger(CloudOmeZarrServerBuilder::class.java)
  }

  class UnauthorizedError : IOException {
    constructor(message: String) : super(message)
  }

  override fun checkImageSupport(uri: URI?, vararg args: String?): UriImageSupport<BufferedImage>? {
    logger.info("Checking image support for CloudOmeZarrServerBuilder: $uri (scheme: ${uri?.scheme}, path: ${uri?.path})")
    if (uri == null) {
      return null
    }

    val effectiveUri = if (uri.scheme == "file" && uri.path.contains("gs:/")) {
      val new = URI.create("gs://${uri.path.substringAfter("gs:/")}")
      logger.info("Converted file URI to GCS: $new; scheme: ${new.scheme}, path: ${new.path}")
      new
    } else {
      uri
    }

    if (!supportedSchema(effectiveUri)) {
      return null
    }

    // TODO: look for an omezarr structure at the URI
    if (!effectiveUri.path.endsWith(".zattrs") && !effectiveUri.path.endsWith(".zarr") && !effectiveUri.path.endsWith(".zarr/")) {
      return null
    }

    // At this point, we should validate that it's a single image
    // In other words the structure should be:
    // - uri (the omezarr root)
    //   - OME
    //   - <image_name from series in OME/.zattrs>
    //     - levels of detail (aka multiscale)
    //       <coordinates>
    // But for now … no

    // I guess we should read in the metadata at this point?
    // But for now … no
    val metadata: ImageServerMetadata? = null

    // Technically 4 is the highest
    // https://github.com/qupath/qupath/blob/a30510b98a6a91b1a79bbf08789565c53bb127b4/qupath-core/src/main/java/qupath/lib/images/servers/ImageServerBuilder.java#L267-L288
    // …but I see the Bioformats Extension uses 5 for its formats eg OMETIFF
    // https://github.com/petebankhead/qupath/blob/a30510b98a6a91b1a79bbf08789565c53bb127b4/qupath-extension-bioformats/src/main/java/qupath/lib/images/servers/bioformats/BioFormatsServerBuilder.java#L76-L77
    // So for now … 5
    // (Do we need 5.1 to take precedence over bio-formats in upcoming qupath 0.6?)
    val supportLevel = 5f

    return UriImageSupport.createInstance(
      this.javaClass,
      supportLevel,
      listOf(
        DefaultImageServerBuilder.createInstance(
          this.javaClass,
          metadata,
          effectiveUri,
          *args
        )
      )
    )
  }

  private fun supportedSchema(uri: URI): Boolean {
    return uri.scheme == "gs" || uri.scheme == "file"
  }

  override fun buildServer(uri: URI?, vararg args: String): ImageServer<BufferedImage>? {
    if (uri == null) {
      return null
    }

    try {
      return CloudOmeZarrServer(uri, *args)
    } catch (e: StorageException) {
      if (e.cause is GoogleJsonResponseException) {
        val responseException = e.cause as GoogleJsonResponseException
        if (responseException.statusCode == 401) {
          logger.error("Error: not authorized to access {}: {}", uri, responseException.message)
          throw UnauthorizedError("Access error. Quit QuPath and run this: gcloud auth application-default login")
        }
      }
      logger.error("Google Cloud Storage exception: {}", e.message, e)
      return null
    } catch (e: Exception) {
      logger.error("Cloud OME-Zarr couldn't open {}: {}", uri, e.message, e)
      return null
    }
  }

  override fun getName(): String {
    return "Cloud OME-Zarr builder"
  }

  override fun getDescription(): String {
    return "Image server for OME-Zarr files stored in the cloud"
  }

  override fun getImageType(): Class<BufferedImage> {
    return BufferedImage::class.java
  }
}
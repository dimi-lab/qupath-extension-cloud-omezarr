package dimilab.qupath.ext.cloud_omezarr

import qupath.lib.images.servers.AbstractTileableImageServer
import qupath.lib.images.servers.ImageChannel
import qupath.lib.images.servers.ImageServerBuilder
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.images.servers.TileRequest
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_INT_ARGB
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.net.URI


class CloudOmeZarrServer(val uri: URI, vararg args: String) : AbstractTileableImageServer() {
  companion object {
    private val logger = org.slf4j.LoggerFactory.getLogger(CloudOmeZarrServer::class.java)
  }

  private val metadata: ImageServerMetadata
  private val serverArgs = args

  init {
    logger.info("Creating CloudOmeZarrServer from $uri with args ${args.joinToString(" ")}")

    // TODO: implement metadata

    // hardcoded values for now:
    metadata = ImageServerMetadata.Builder()
      .width(1000)
      .height(1000)
      .levelsFromDownsamples(1.0)
      .channels(ImageChannel.getDefaultRGBChannels())
      .preferredTileSize(100, 100)
      .build()
  }

  override fun getURIs(): MutableCollection<URI> {
    return arrayListOf(uri)
  }

  override fun getServerType(): String {
    return "Cloud OME-ZARR"
  }

  override fun getOriginalMetadata(): ImageServerMetadata {
    return metadata
  }

  override fun createServerBuilder(): ImageServerBuilder.ServerBuilder<BufferedImage> {
    return DefaultImageServerBuilder.createInstance(
      CloudOmeZarrServerBuilder::class.java,
      metadata,
      uri,
      *serverArgs,
    )
  }

  override fun createID(): String {
    return "CloudOmeZarrServer: $uri ${serverArgs.joinToString(" ")}"
  }

  override fun readTile(tileRequest: TileRequest?): BufferedImage {
    // TODO: implement tile reading

    logger.debug("Reading tile: {}", tileRequest)

    val width = tileRequest!!.tileWidth
    val height = tileRequest.tileHeight

    val img = BufferedImage(width, height, TYPE_INT_ARGB)

    val rgb = Color(255, 0, 255).rgb
    val startW = (width * 0.1).toInt()
    val endW = (width * 0.9).toInt()
    val startH = (height * 0.1).toInt()
    val endH = (height * 0.9).toInt()
    (startW..endW).forEach { x ->
      (startH..endH).forEach { y ->
        img.setRGB(x, y, rgb)
      }
    }

    return img
  }
}
package dimilab.qupath.ext.cloud_omezarr

import com.bc.zarr.ZarrArray
import com.bc.zarr.ZarrGroup
import loci.formats.ome.OMEXMLMetadata
import qupath.lib.color.ColorModelFactory
import qupath.lib.images.servers.AbstractTileableImageServer
import qupath.lib.images.servers.ImageServerBuilder
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.images.servers.ImageServerMetadata.ImageResolutionLevel
import qupath.lib.images.servers.TileRequest
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.io.IOException
import java.net.URI
import kotlin.io.path.toPath


class CloudOmeZarrServer(private val zarrBaseUri: URI, vararg args: String) : AbstractTileableImageServer() {
  companion object {
    private val logger = org.slf4j.LoggerFactory.getLogger(CloudOmeZarrServer::class.java)
  }

  data class OmeZarrMetadata(
    val imageName: String,
    val omeXml: OMEXMLMetadata,
  )

  private val metadata: ImageServerMetadata
  private val serverArgs = args

  data class ScaleLevel(
    val path: String,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val zarrArray: ZarrArray,
    // TODO: coordinateTransforms
  )

  // The image has several levels of detail.
  private val scaleLevels: List<ScaleLevel>

  // The color model converts n-channel pixels into RGB values.
  private val colorModel: ColorModel

  init {
    logger.info("Creating CloudOmeZarrServer from $zarrBaseUri with args ${args.joinToString(" ")}")

    // Open the Zarray at the root
    val rootZarr = ZarrGroup.open(zarrBaseUri.toPath())
    if (rootZarr.attributes["bioformats2raw.layout"] != 3) {
      throw IOException("Expected a Zarr array with layout 3, but found ${rootZarr.attributes["bioformats2raw.layout"]}")
    }

    val omeZarrMetadata = readOmeZarrMetadata(rootZarr)

    val imageZarr = rootZarr.openSubGroup(omeZarrMetadata.imageName)
    scaleLevels = buildScaleLevels(imageZarr)

    val omeMetadata = omeZarrMetadata.omeXml
    val channels = omeChannelsToQuPath(omeMetadata)

    val omePixelType = omeMetadata.getPixelsType(0)
    checkPixelType(omePixelType, omeMetadata.getPixelsBigEndian(0), scaleLevels)
    val pixelType = omeXmlPixelTypeToQupath(omePixelType)
    colorModel = ColorModelFactory.createColorModel(pixelType, channels)

    val levelsBuilder = ImageResolutionLevel.Builder(scaleLevels[0].width, scaleLevels[0].height)
    scaleLevels.forEach { levelsBuilder.addLevel(it.width, it.height) }

    metadata =
      ImageServerMetadata.Builder()
        .width(scaleLevels[0].width)
        .height(scaleLevels[0].height)
        .levels(levelsBuilder.build())
        .pixelType(pixelType)
        .channels(channels)
        .preferredTileSize(scaleLevels[0].tileWidth, scaleLevels[0].tileHeight).build()
  }

  private fun readOmeZarrMetadata(omezarr: ZarrGroup): OmeZarrMetadata {
    val metadataZarr = omezarr.openSubGroup("OME")
    val imageNames = metadataZarr.attributes["series"] as List<*>
    if (imageNames.size != 1) {
      throw IOException("Expected a single image in OME-Zarr, but found: $imageNames")
    }

    val omeBaseUri = zarrBaseUri.resolve("OME/")
    val omeMetadata = parseOmeXmlMetadata(omeBaseUri)

    if (omeMetadata.imageCount != 1) {
      throw IOException("Expected a single image in OME-Zarr, but found ${omeMetadata.imageCount}")
    }
    if (omeMetadata.plateCount != 0) {
      throw IOException("Can't handle plates")
    }

    return OmeZarrMetadata(
      imageName = imageNames[0] as String,
      omeXml = omeMetadata,
    )
  }

  private fun buildScaleLevels(imageZarr: ZarrGroup): List<ScaleLevel> {
    val multiscales = imageZarr.attributes["multiscales"] as ArrayList<*>
    if (multiscales.size != 1) {
      throw IOException("Expected a single multiscale in OME-Zarr, but found ${multiscales.size}")
    }

    val scaleLevelDefs = (multiscales[0] as Map<*, *>)["datasets"] as ArrayList<*>

    // TODO: get these from axis data
    val xDimension = 4
    val yDimension = 3

    return scaleLevelDefs.map {
      val scaleLevelDef = it as Map<*, *>
      val path = scaleLevelDef["path"] as String

      val scaledArray = imageZarr.openArray(path)

      ScaleLevel(
        path = path,
        width = scaledArray.shape[xDimension],
        height = scaledArray.shape[yDimension],
        tileWidth = scaledArray.chunks[xDimension],
        tileHeight = scaledArray.chunks[yDimension],
        zarrArray = scaledArray,
      )
    }
  }

  override fun getURIs(): MutableCollection<URI> {
    return arrayListOf(zarrBaseUri)
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
      zarrBaseUri,
      *serverArgs,
    )
  }

  override fun createID(): String {
    return "CloudOmeZarrServer: $zarrBaseUri ${serverArgs.joinToString(" ")}"
  }

  override fun readTile(tileRequest: TileRequest?): BufferedImage {
    if (tileRequest == null) {
      throw IllegalArgumentException("Tile request cannot be null")
    }
    if (tileRequest.level < 0 || tileRequest.level >= scaleLevels.size) {
      throw IllegalArgumentException("Tile level ${tileRequest.level} out of bounds for ${scaleLevels.size} levels")
    }

    logger.debug(
      "Reading tile x={} y={} width={} height={} level={} channels={}",
      tileRequest.tileX,
      tileRequest.tileY,
      tileRequest.tileWidth,
      tileRequest.tileHeight,
      tileRequest.level,
      metadata.channels.size
    )

    return renderZarrToBufferedImage(
      scaleLevels[tileRequest.level].zarrArray,
      colorModel,
      tileRequest.tileX,
      tileRequest.tileY,
      tileRequest.tileWidth,
      tileRequest.tileHeight,
      metadata.pixelType,
      metadata.channels.size
    )
  }
}
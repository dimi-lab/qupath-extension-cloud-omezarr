package dimilab.qupath.ext.cloud_omezarr

import com.bc.zarr.ZarrArray
import com.bc.zarr.ZarrGroup
import loci.common.RandomAccessInputStream
import loci.common.services.ServiceFactory
import loci.common.xml.XMLTools
import loci.formats.ome.OMEXMLMetadata
import loci.formats.services.OMEXMLService
import org.xml.sax.SAXException
import qupath.lib.images.servers.*
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder
import qupath.lib.images.servers.ImageServerMetadata.ImageResolutionLevel
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_INT_ARGB
import java.io.IOException
import java.net.URI
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.TransformerException
import kotlin.io.path.absolutePathString
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
    val zarrArray: ZarrArray,
    // TODO: coordinateTransforms
  )

  private val scaleLevels: List<ScaleLevel>

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

    val levelsBuilder = ImageResolutionLevel.Builder(scaleLevels[0].width, scaleLevels[0].height)
    scaleLevels.forEach { levelsBuilder.addLevel(it.width, it.height) }

    metadata =
      ImageServerMetadata.Builder()
        .width(scaleLevels[0].width)
        .height(scaleLevels[0].height)
        .levels(levelsBuilder.build())
        .channels(channels)
        // TODO make this the 1st level's tile size
        .preferredTileSize(100, 100).build()
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
package dimilab.qupath.ext.omezarr

import com.bc.zarr.ZarrGroup
import dimilab.omezarr.*
import dimilab.qupath.quietLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import loci.formats.ome.OMEXMLMetadata
import org.apache.commons.cli.*
import qupath.lib.color.ColorModelFactory
import qupath.lib.gui.QuPathGUI
import qupath.lib.images.servers.AbstractTileableImageServer
import qupath.lib.images.servers.ImageServerBuilder
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.images.servers.ImageServerMetadata.ImageResolutionLevel
import qupath.lib.images.servers.TileRequest
import qupath.lib.io.PathIO
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjectReader
import qupath.lib.regions.RegionRequest
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.*


class CloudOmeZarrServer(private val zarrBaseUri: URI, vararg args: String) : AbstractTileableImageServer(),
  PathObjectReader {
  companion object {
    private val logger = org.slf4j.LoggerFactory.getLogger(CloudOmeZarrServer::class.java)
  }

  private val zarrRoot: Path

  data class OmeZarrArgs(
    val remoteQpDataPath: URI? = null,
    val changesetRoot: URI? = null,
  )

  data class OmeZarrMetadata(
    val imageName: String,
    val omeXml: OMEXMLMetadata,
  )

  private val metadata: ImageServerMetadata
  private val originalArgs = arrayOf(*args)
  val serverArgs: OmeZarrArgs

  // The image has several levels of detail.
  private val scaleLevels: List<ScaleLevel>

  // The color model converts n-channel pixels into RGB values.
  private val colorModel: ColorModel

  init {
    logger.info("Creating CloudOmeZarrServer from $zarrBaseUri with args: ${args.joinToString(" ")}")

    serverArgs = parseArgs(originalArgs)

    zarrRoot = getZarrRoot(zarrBaseUri)
    logger.info("Loading base array at $zarrRoot")
    val rootZarr = ZarrGroup.open(CloudZarrStore.fromPath(zarrRoot))
    if (rootZarr.attributes["bioformats2raw.layout"] != 3) {
      throw IOException("Expected a Zarr array with layout 3, but found ${rootZarr.attributes["bioformats2raw.layout"]}")
    }

    logger.info("Reading OME-Zarr shape & metadata")

    val omeZarrMetadata = readOmeZarrMetadata(rootZarr)
    val omeMetadata = omeZarrMetadata.omeXml

    val imageZarr = rootZarr.openSubGroup(omeZarrMetadata.imageName)

    logger.info("Reading scale levels from ${omeZarrMetadata.imageName}")
    scaleLevels = buildScaleLevels(imageZarr)

    logger.info("Getting pixel & channel information from OME metadata")
    val channels = omeChannelsToQuPath(omeMetadata)

    val omePixelType = omeMetadata.getPixelsType(0)
    checkPixelType(omePixelType, scaleLevels)
    val pixelType = omeXmlPixelTypeToQupath(omePixelType)
    colorModel = ColorModelFactory.createColorModel(pixelType, channels)
    logger.info("Pixel type: $pixelType; channels: ${channels.size}")

    val levelsBuilder = ImageResolutionLevel.Builder(scaleLevels[0].width, scaleLevels[0].height)
    scaleLevels.forEach { levelsBuilder.addLevel(it.width, it.height) }

    logger.info("Creating QuPath metadata")
    metadata =
      ImageServerMetadata.Builder()
        .width(scaleLevels[0].width)
        .height(scaleLevels[0].height)
        .levels(levelsBuilder.build())
        .pixelType(pixelType)
        .channels(channels)
        .preferredTileSize(scaleLevels[0].tileWidth, scaleLevels[0].tileHeight).build()
  }

  private fun parseArgs(args: Array<String>): OmeZarrArgs {
    val options = Options()

    val remoteFileOption = Option.builder().longOpt("qpdata-path")
      .argName("path")
      .hasArg()
      .desc("set the remote QuPath qpdata path")
      .build()
    options.addOption(remoteFileOption)
    val changesetRootOption = Option.builder().longOpt("changeset-root")
      .argName("changeset-root")
      .hasArg()
      .desc("set the remote changeset root path, gs://bucket/path")
      .build()
    options.addOption(changesetRootOption)

    val parser: CommandLineParser = DefaultParser()
    val line: CommandLine? = parser.parse(options, args)

    val remoteQpDataUri = if (line?.hasOption("qpdata-path") == true) {
      URI.create(line.getOptionValue("qpdata-path"))
    } else {
      null
    }

    val changesetRootUri = if (line?.hasOption("changeset-root") == true) {
      URI.create(line.getOptionValue("changeset-root"))
    } else {
      null
    }

    return OmeZarrArgs(
      remoteQpDataPath = remoteQpDataUri,
      changesetRoot = changesetRootUri,
    )
  }

  fun getImageArgs(): OmeZarrArgs {
    return serverArgs
  }

  override fun close() {
    zarrRoot.fileSystem.close()
    super.close()
  }

  private fun readOmeZarrMetadata(omezarr: ZarrGroup): OmeZarrMetadata {
    val metadataZarr = omezarr.openSubGroup("OME")
    val imageNames = metadataZarr.attributes["series"] as List<*>
    if (imageNames.size != 1) {
      throw IOException("Expected a single image in OME-Zarr, but found: $imageNames")
    }

    val omeMetadata = parseOmeXmlMetadata(zarrRoot.resolve("OME"))

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

    return runBlocking(Dispatchers.IO) {
      val workers = scaleLevelDefs.map {
        async {
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

      workers.awaitAll()
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
      *originalArgs,
    )
  }

  override fun createID(): String {
    return "CloudOmeZarrServer: $zarrBaseUri ${originalArgs.joinToString(" ")}"
  }

  override fun readTile(tileRequest: TileRequest?): BufferedImage {
    if (tileRequest == null) {
      throw IllegalArgumentException("Tile request cannot be null")
    }
    if (tileRequest.level < 0 || tileRequest.level >= scaleLevels.size) {
      throw IllegalArgumentException("Tile level ${tileRequest.level} out of bounds for ${scaleLevels.size} levels")
    }

    // NOTE: the selectedChannels read mechanism only works because we clear
    // the region store cache each time the image display changes. Currently,
    // that happens in the Annotation Syncer (our central listener).
    val selectedChannels = getSelectedChannels()

    logger.debug(
      "Reading tile x={} y={} width={} height={} level={} channels={} selectedChannels={}",
      tileRequest.tileX,
      tileRequest.tileY,
      tileRequest.tileWidth,
      tileRequest.tileHeight,
      tileRequest.level,
      metadata.channels.size,
      selectedChannels ?: "all",
    )

    return renderZarrToBufferedImage(
      scaleLevels[tileRequest.level].zarrArray,
      colorModel,
      tileRequest.tileX,
      tileRequest.tileY,
      tileRequest.tileWidth,
      tileRequest.tileHeight,
      tileRequest.level,
      metadata.pixelType,
      metadata.channels.size,
      selectedChannels,
    )
  }

  public override fun getCache(): MutableMap<RegionRequest?, BufferedImage?>? {
    return super.getCache()
  }

  private fun getSelectedChannels(): Set<Int>? {
    val imageDisplay = QuPathGUI.getInstance()?.viewer?.imageDisplay

    if (imageDisplay?.imageData?.server != this) {
      // The image display is not currently displaying this image, so don't
      // use it for channels. This happens in thumbnail mode for example.
      logger.debug("Skipping selected channels optimization because image display is not showing this server")
      return null
    }
    val matchedChannels = imageDisplay?.selectedChannels()?.map { it ->
      val selectedChannel = it.name.substringBeforeLast(" (C")
      metadata.channels.indexOfFirst { it.name.trim() == selectedChannel.trim() }
    }?.toSet()

    return matchedChannels.let {
      if (it?.contains(-1) == true) {
        logger.warn(
          "One of display channels {} not found in image channel list {}",
          imageDisplay?.selectedChannels()?.map { x -> x.name },
          metadata.channels.map { x -> x.name },
        )
        null
      } else {
        it
      }
    }
  }

  override fun readPathObjects(): MutableCollection<PathObject> {
    if (serverArgs.remoteQpDataPath == null) {
      logger.error("Can't load path objects from null remote path")
      return Collections.emptyList()
    }

    logger.info("Reading image data from remote path: ${serverArgs.remoteQpDataPath}")
    val startTime = System.currentTimeMillis()
    val downloads = downloadUrisToTemp(listOf(serverArgs.remoteQpDataPath))
    logger.info("Downloaded remote path in ${System.currentTimeMillis() - startTime} ms")
    val localPath = downloads[serverArgs.remoteQpDataPath] ?: throw IOException("Failed to download remote path")

    logger.debug("Opening image data from local path: $localPath")

    val imageData = quietLoggers("qupath.lib.objects.MetadataMap") {
      PathIO.readImageData<BufferedImage>(localPath, null, this, null)
    }

    val objects = imageData.hierarchy.rootObject.childObjects.toMutableList()
    logger.info("Image data read; found {} objects at root", objects.size)
    return objects
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CloudOmeZarrServer) return false

    if (zarrBaseUri != other.zarrBaseUri) return false
    if (serverArgs != other.serverArgs) return false

    return true
  }

  override fun hashCode(): Int {
    var result = zarrBaseUri.hashCode()
    result = 31 * result + serverArgs.hashCode()
    return result
  }
}
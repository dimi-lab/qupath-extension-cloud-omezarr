package dimilab.qupath.ext.omezarr

import com.bc.zarr.ZarrArray
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.StorageOptions
import com.google.cloud.storage.contrib.nio.CloudStorageConfiguration
import com.google.cloud.storage.contrib.nio.CloudStorageFileSystem
import dimilab.qupath.ext.omezarr.OmeZarrUtils.Companion.logger
import kotlinx.coroutines.*
import org.slf4j.Logger
import qupath.lib.images.servers.PixelType
import java.awt.image.*
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.ProviderNotFoundException
import ome.xml.model.enums.PixelType as OmePixelType

class OmeZarrUtils {
  companion object {
    val logger: Logger = org.slf4j.LoggerFactory.getLogger(OmeZarrUtils::class.java)
  }
}

fun omeXmlPixelTypeToZarrDtype(omeXmlPixelType: OmePixelType): com.bc.zarr.DataType {
  return when (omeXmlPixelType) {
    OmePixelType.INT8 -> com.bc.zarr.DataType.i1
    OmePixelType.UINT8 -> com.bc.zarr.DataType.u1
    OmePixelType.INT16 -> com.bc.zarr.DataType.i2
    OmePixelType.UINT16 -> com.bc.zarr.DataType.u2
    OmePixelType.INT32 -> com.bc.zarr.DataType.i4
    OmePixelType.UINT32 -> com.bc.zarr.DataType.u4
    OmePixelType.FLOAT -> com.bc.zarr.DataType.f4
    OmePixelType.DOUBLE -> com.bc.zarr.DataType.f8
    else -> throw IllegalArgumentException("Unsupported pixel type: $omeXmlPixelType")
  }
}

fun checkPixelType(
  omePixelType: OmePixelType,
  scaleLevels: List<CloudOmeZarrServer.ScaleLevel>,
) {
  // This checks that the OME metadata and zarr were generated together correctly.

  scaleLevels.forEach { scaleLevel ->
    val zarrDtype = scaleLevel.zarrArray.dataType
    if (zarrDtype != omeXmlPixelTypeToZarrDtype(omePixelType)) {
      throw IllegalArgumentException(
        "Zarr dtype ${zarrDtype.name} does not match OME XML pixel type ${omePixelType.name} for scale level ${scaleLevel.path}"
      )
    }
  }
}

private fun makeBuffer(width: Int, height: Int, numChannels: Int, pixelType: PixelType): DataBuffer {
  return when (pixelType) {
    PixelType.UINT8 -> DataBufferByte(width * height, numChannels)
    PixelType.INT8 -> DataBufferByte(width * height, numChannels)
    PixelType.UINT16 -> DataBufferUShort(width * height, numChannels)
    PixelType.INT16 -> DataBufferShort(width * height, numChannels)
    PixelType.UINT32 -> DataBufferInt(width * height, numChannels)
    PixelType.INT32 -> DataBufferInt(width * height, numChannels)
    PixelType.FLOAT32 -> DataBufferFloat(width * height, numChannels)
    PixelType.FLOAT64 -> DataBufferDouble(width * height, numChannels)
  }
}

fun CoroutineScope.launchChannelReader(
  zarrArray: ZarrArray,
  raster: WritableRaster,
  readShape: IntArray,
  c: Int,
  x: Int,
  y: Int,
  width: Int,
  height: Int,
) = async {
  val readOffset = intArrayOf(0, c, 0, y, x)
  logger.trace("Reading shape {} at offset {}", readShape, readOffset)
  val readData = zarrArray.read(readShape, readOffset)

  when (readData) {
    is DoubleArray -> {
      raster.setSamples(0, 0, width, height, c, readData)
    }

    is FloatArray -> {
      raster.setSamples(0, 0, width, height, c, readData)
    }

    is ByteArray -> {
      raster.setSamples(0, 0, width, height, c, readData.map { it.toInt() }.toIntArray())
    }

    is ShortArray -> {
      raster.setSamples(0, 0, width, height, c, readData.map { it.toInt() }.toIntArray())
    }

    is IntArray -> {
      raster.setSamples(0, 0, width, height, c, readData)
    }

    is LongArray -> {
      raster.setSamples(0, 0, width, height, c, readData.map { it.toInt() }.toIntArray())
    }

    else -> {
      throw IllegalArgumentException("Unsupported data type: ${readData::class.java}")
    }
  }
}

fun renderZarrToBufferedImage(
  zarrArray: ZarrArray,
  colorModel: ColorModel,
  x: Int,
  y: Int,
  width: Int,
  height: Int,
  pixelType: PixelType,
  numChannels: Int,
): BufferedImage {
  val dataBuffer = makeBuffer(width, height, numChannels, pixelType)
  val sampleModel = BandedSampleModel(dataBuffer.dataType, width, height, dataBuffer.numBanks)
  val raster = WritableRaster.createWritableRaster(sampleModel, dataBuffer, null)

  val readShape = intArrayOf(1, 1, 1, height, width)

  runBlocking(Dispatchers.IO) {
    val workers = (0 until numChannels).map { c ->
      launchChannelReader(zarrArray, raster, readShape, c, x, y, width, height)
    }

    workers.awaitAll()
  }

  return BufferedImage(colorModel, raster, false, null)
}

fun uriToFileSystem(uri: URI): FileSystem {
  return when (uri.scheme) {
    "gs" -> {
      val b = BlobId.fromGsUtilUri(uri.toString())
      val config = CloudStorageConfiguration.DEFAULT
      CloudStorageFileSystem.forBucket(b.bucket, config, StorageOptions.getDefaultInstance())
    }

    else -> {
      // Unix file system expects root path (path = '/')
      FileSystems.getFileSystem(uri.resolve("/"))
    }
  }
}

fun getZarrRoot(uri: URI): Path {
  val zarrFs = try {
    uriToFileSystem(uri)
  } catch (e: ProviderNotFoundException) {
    logger.error("No java.nio FileSystemProvider found for scheme '{}', uri: {}", uri.scheme, uri, e)
    throw IllegalArgumentException("Unsupported scheme '${uri.scheme}'")
  }

  // As a convenience, if the URI ends with .zattrs, we use the parent directory as the root.
  // In some applications it's hard to select a directory, so this lets the user select the file instead.
  if (uri.path.endsWith("/.zattrs")) {
    logger.info("Zarr root is .zattrs, using directory instead")
    return zarrFs.getPath(uri.resolve("./").path)
  }

  return zarrFs.getPath(uri.path)
}
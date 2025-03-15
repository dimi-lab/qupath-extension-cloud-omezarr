package dimilab.qupath.ext.cloud_omezarr

import com.bc.zarr.ZarrArray
import org.slf4j.Logger
import qupath.lib.color.ColorModelFactory
import qupath.lib.images.servers.PixelType
import java.awt.image.*
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
  omeBigEndian: Boolean,
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

    val zarrBigEndian = scaleLevel.zarrArray.byteOrder == java.nio.ByteOrder.BIG_ENDIAN
    if (omeBigEndian != zarrBigEndian) {
      throw IllegalArgumentException(
        "Zarr bigendian=${scaleLevel.zarrArray.byteOrder} does not match OME XML bigendian=$omeBigEndian for scale level ${scaleLevel.path}"
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
    else -> throw IllegalArgumentException("Unsupported pixel type: $pixelType")
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

  (0 until numChannels).forEach { c ->
    val readOffset = intArrayOf(0, c, 0, y, x)
    val readData = zarrArray.read(readShape, readOffset)

    when (readData) {
      is DoubleArray -> {
        raster.setSamples(0, 0, width, height, c, readData)
      }

      is FloatArray -> {
        raster.setSamples(0, 0, width, height, c, readData)
      }

      is ByteArray, is ShortArray, is IntArray, is LongArray -> {
        raster.setSamples(0, 0, width, height, c, readData as IntArray)
      }

      else -> {
        throw IllegalArgumentException("Unsupported data type: ${readData::class.java}")
      }
    }
  }

  return BufferedImage(colorModel, raster, false, null)
}
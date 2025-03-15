package dimilab.qupath.ext.cloud_omezarr

import dimilab.qupath.ext.cloud_omezarr.OmeXmlUtils.Companion.logger
import loci.common.RandomAccessInputStream
import loci.common.services.ServiceFactory
import loci.common.xml.XMLTools
import loci.formats.ome.OMEXMLMetadata
import loci.formats.services.OMEXMLService
import ome.xml.model.enums.PixelType
import org.slf4j.Logger
import org.xml.sax.SAXException
import qupath.lib.common.ColorTools
import qupath.lib.images.servers.ImageChannel
import java.io.IOException
import java.net.URI
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.TransformerException
import kotlin.io.path.absolutePathString
import kotlin.io.path.toPath


class OmeXmlUtils {
  companion object {
    val logger: Logger = org.slf4j.LoggerFactory.getLogger(OmeZarrUtils::class.java)
  }
}

fun parseOmeXmlMetadata(omeRoot: URI): OMEXMLMetadata {
  assert(omeRoot.path.endsWith("/"))

  val xml = readOmeXml(omeRoot.resolve("METADATA.ome.xml"))

  val service = ServiceFactory().getInstance(OMEXMLService::class.java)
  return service.createOMEXMLMetadata(xml)
}


fun readOmeXml(uri: URI): String {
  val metadataFilePath = uri.toPath()

  val omeDocument = try {
    val measurement = RandomAccessInputStream(metadataFilePath.absolutePathString())
    XMLTools.parseDOM(measurement)
  } catch (e: ParserConfigurationException) {
    throw IOException(e)
  } catch (e: SAXException) {
    throw IOException(e)
  }
  omeDocument.documentElement.normalize()

  try {
    return XMLTools.getXML(omeDocument)
  } catch (e: TransformerException) {
    // logger vs throw?
    throw IOException(e)
  }
}

fun omeChannelsToQuPath(omeMetadata: OMEXMLMetadata): List<ImageChannel> {
  return (0 until omeMetadata.getChannelCount(0)).map { channelNum ->
    val name = omeMetadata.getChannelName(0, channelNum)
    val color = omeMetadata.getChannelColor(0, channelNum)
    val colorVal = ColorTools.packARGB(color.alpha, color.red, color.green, color.blue)
    ImageChannel.getInstance(name, colorVal)
  }
}

fun omeXmlPixelTypeToQupath(omeXmlPixelType: PixelType): qupath.lib.images.servers.PixelType {
  when (omeXmlPixelType) {
    PixelType.BIT -> {
      logger.warn("Pixel type is BIT! This is not currently supported by QuPath.")
      return qupath.lib.images.servers.PixelType.UINT8
    }

    PixelType.INT8 -> {
      logger.warn("Pixel type is INT8! This is not currently supported by QuPath.")
      return qupath.lib.images.servers.PixelType.INT8
    }

    PixelType.UINT8 -> return qupath.lib.images.servers.PixelType.UINT8
    PixelType.INT16 -> return qupath.lib.images.servers.PixelType.INT16
    PixelType.UINT16 -> return qupath.lib.images.servers.PixelType.UINT16

    PixelType.INT32 -> return qupath.lib.images.servers.PixelType.INT32
    PixelType.UINT32 -> {
      logger.warn("Pixel type is UINT32! This is not currently supported by QuPath.")
      return qupath.lib.images.servers.PixelType.UINT32
    }

    PixelType.FLOAT -> return qupath.lib.images.servers.PixelType.FLOAT32
    PixelType.DOUBLE -> return qupath.lib.images.servers.PixelType.FLOAT64

    else -> throw IllegalArgumentException("Unsupported pixel type: $omeXmlPixelType")
  }
}

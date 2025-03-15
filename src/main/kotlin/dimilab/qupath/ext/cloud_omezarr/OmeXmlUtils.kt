package dimilab.qupath.ext.cloud_omezarr

import loci.common.RandomAccessInputStream
import loci.common.services.ServiceFactory
import loci.common.xml.XMLTools
import loci.formats.ome.OMEXMLMetadata
import loci.formats.services.OMEXMLService
import org.xml.sax.SAXException
import qupath.lib.common.ColorTools
import qupath.lib.images.servers.ImageChannel
import java.io.IOException
import java.net.URI
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.TransformerException
import kotlin.io.path.absolutePathString
import kotlin.io.path.toPath


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
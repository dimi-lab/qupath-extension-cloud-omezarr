package dimilab.qupath.pathobjects

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dimilab.qupath.pathobjects.Utils.Companion.logger
import qupath.lib.common.ColorTools
import qupath.lib.objects.PathObject
import java.time.Instant

class Utils {
  companion object {
    val logger = org.slf4j.LoggerFactory.getLogger(Utils::class.java)
  }
}


fun PathObject.toJsonObject(): com.google.gson.JsonObject {
  // Is this truly the best way: going from object to string then parsed to JSON object?
  val gson = qupath.lib.io.GsonTools.getInstance()
  val jsonStr = gson.toJson(this)
  return gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
}

class InstantTypeAdapter : TypeAdapter<Instant>() {
  override fun write(out: JsonWriter, value: Instant?) {
    if (value == null) {
      out.nullValue()
      return
    }
    out.value(value.toString())
  }

  override fun read(input: JsonReader): Instant? {
    val str = input.nextString()
    return if (str == null) null else Instant.parse(str)
  }
}

class QuPathColorAdapter : TypeAdapter<Int>() {
  override fun write(out: JsonWriter, value: Int?) {
    if (value == null) {
      out.nullValue()
      return
    }
    out.value(value.toString())
  }

  override fun read(input: JsonReader): Int? {
    val next = input.peek()

    if (next == JsonToken.BEGIN_ARRAY) {
      input.beginArray()
      val r = input.nextInt()
      val g = input.nextInt()
      val b = input.nextInt()
      input.endArray()
      return ColorTools.packRGB(r, g, b)
    } else if (next == JsonToken.NUMBER) {
      return input.nextInt()
    } else {
      logger.error("Unexpected token when reading color: {}", next)
      input.skipValue()
      return null
    }
  }
}

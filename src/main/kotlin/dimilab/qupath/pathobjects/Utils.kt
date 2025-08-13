package dimilab.qupath.pathobjects

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import qupath.lib.objects.PathObject
import java.time.Instant


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

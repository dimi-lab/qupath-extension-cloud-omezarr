package dimilab.qupath.pathobjects

import qupath.lib.objects.PathObject


fun PathObject.toJsonObject(): com.google.gson.JsonObject {
  // Is this truly the best way: going from object to string then parsed to JSON object?
  val gson = qupath.lib.io.GsonTools.getInstance()
  val jsonStr = gson.toJson(this)
  return gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
}

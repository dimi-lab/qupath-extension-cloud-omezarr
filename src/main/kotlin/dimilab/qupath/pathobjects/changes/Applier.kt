package dimilab.qupath.pathobjects.changes

import com.google.gson.JsonObject
import dimilab.qupath.pathobjects.QuPathColorAdapter
import org.slf4j.LoggerFactory
import qupath.lib.io.GsonTools
import qupath.lib.measurements.MeasurementList
import qupath.lib.objects.*
import qupath.lib.objects.classes.PathClass
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.roi.interfaces.ROI
import java.util.*

object Applier {
  private val logger = LoggerFactory.getLogger(Applier::class.java)
  private val gson = GsonTools.getInstance()

  fun applyEventsToHierarchy(events: Collection<Event>, hierarchy: PathObjectHierarchy) {
    val hierarchyObjects = hierarchy.getAllObjects(false).associateBy { it.id }.toMutableMap()

    // Apply the events in order, but without firing any QuPath events.
    // Fire a single event at the end.

    val changesApplied = events.map { event ->
      when (event) {
        is CreateEvent -> applyCreateEvent(hierarchy, hierarchyObjects, event)
        is EditEvent -> applyEditEvent(hierarchyObjects, event)
        is DeleteEvent -> applyDeleteEvent(hierarchy, hierarchyObjects, event)
      }
    }.any()

    // Fire an overall change event. Let listeners sort it out…
    if (changesApplied) {
      hierarchy.fireHierarchyChangedEvent(this)
    }
  }

  private fun applyCreateEvent(
    hierarchy: PathObjectHierarchy,
    hierarchyObjects: MutableMap<UUID, PathObject>,
    event: CreateEvent,
  ): Boolean {
    if (hierarchyObjects.containsKey(event.id)) {
      logger.warn("Skipping create event for object that already exists: ${event.id}")
      return false
    }
    val newObj = gson.fromJson(event.fields, PathObject::class.java)
    hierarchy.addObject(newObj, false)
    hierarchyObjects[event.id] = newObj
    return true
  }

  private fun applyEditEvent(hierarchyObjects: MutableMap<UUID, PathObject>, event: EditEvent): Boolean {
    val obj = hierarchyObjects[event.id]
    if (obj == null) {
      logger.warn("Skipping edit event for object that does not exist: ${event.id}")
      return false
    }

    return when (obj) {
      is PathDetectionObject -> {
        // We don't track changes to detection objects, this shouldn't happen
        logger.error("Skipping unexpected detection object edit")
        false
      }

      is PathAnnotationObject -> {
        applyAnnotationDiff(obj, event.diff)
      }

      is PathRootObject -> {
        logger.error("Skipping unexpected changes to root object")
        false
      }

      else -> {
        logger.warn("Unknown object type: ${obj.javaClass.name}")
        false
      }
    }
  }

  private fun applyAnnotationDiff(obj: PathObject, diff: JsonObject): Boolean {
    var appliedChanges = false

    // Apply new ROI first, it invalidates measurements (which could be set below)
    if (diff.has("geometry")) {
      if (obj !is PathROIObject) {
        logger.error("Non-ROI object id={} has a geometry change", obj.id)
      } else {
        obj.setROI(gson.fromJson(diff["geometry"], ROI::class.java))
        appliedChanges = true
      }
    }

    if (diff.has("properties")) {
      val propertiesDiff = diff["properties"].asJsonObject

      propertiesDiff.entrySet().forEach { (key, value) ->
        when (key) {
          "name" -> {
            val newName = runCatching { value.asString }.getOrNull()
            if (newName != null && obj.name != newName) {
              obj.name = newName
              appliedChanges = true
            }
          }

          "color" -> {
            val parsedColor = QuPathColorAdapter().fromJsonTree(value)
            parsedColor?.also {
              if (obj.color != it) {
                obj.color = it
                appliedChanges = true
              }
            }
          }

          "classification" -> {
            val classification = gson.fromJson(value, PathClass::class.java)
            if (obj.pathClass != classification) {
              obj.pathClass = classification
              appliedChanges = true
            }
          }

          "measurements" -> {
            // Instantiate the old/new maps for performance
            // (the qupath maps use a list backing store)
            val oldMeasurements = obj.measurements.entries.associate { it.key to it.value }
            val newMeasurements = gson.fromJson(value, MeasurementList::class.java).asMap()
            if (oldMeasurements != newMeasurements) {
              obj.measurements.clear()
              obj.measurements.putAll(newMeasurements)
              appliedChanges = true
            }
          }

          "metadata" -> {
            runCatching {
              value.asJsonObject
            }.onSuccess { newMetadataJson ->
              val newMetadata = newMetadataJson.entrySet().mapNotNull {
                if (value.isJsonPrimitive) {
                  it.key to it.value.asString
                } else {
                  null
                }
              }.toMap()

              obj.metadata.clear()
              obj.metadata.putAll(newMetadata)
              appliedChanges = true
            }.onFailure {
              logger.error("Properties 'metadata' field not an object", it)
            }
          }

          else -> {
            logger.warn("Unknown property change: $key")
          }
        }
      }

    }

    return appliedChanges
  }

  private fun applyDeleteEvent(
    hierarchy: PathObjectHierarchy,
    hierarchyObjects: MutableMap<UUID, PathObject>,
    event: DeleteEvent,
  ): Boolean {
    val obj = hierarchyObjects[event.id]
    if (obj == null) {
      logger.warn("Skipping delete event for object that does not exist: ${event.id}")
      return false
    }
    hierarchy.removeObjectWithoutUpdate(obj, true)
    hierarchyObjects.remove(event.id)
    return true
  }
}
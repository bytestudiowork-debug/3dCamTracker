package com.trackstudio.app.viewmodel

import android.content.Context
import com.trackstudio.app.tracking.CameraFeatureObservation
import com.trackstudio.app.tracking.TrackingAnchorSegment
import com.trackstudio.app.util.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores the viewer-facing tracking result inside the project cache.
 * This keeps completed tracking overlays and solve diagnostics available
 * after the app is closed or the device is restarted.
 */
internal object TrackingResultStore {
    data class Snapshot(
        val pointsByFrame: Map<Int, List<CameraFeatureObservation>>,
        val anchorSegmentsByFrame: Map<Int, List<TrackingAnchorSegment>>,
        val summary: CameraTrackingSummary,
        val anchorX: Double,
        val anchorY: Double,
        val anchorZ: Double,
        val defaultAnchorX: Double,
        val defaultAnchorY: Double,
        val defaultAnchorZ: Double,
        val anchorAxisLength: Double
    )

    private fun file(context: Context, projectId: String) =
        File(FileUtils.projectCacheDir(context, projectId), "tracking_result.json")

    fun save(context: Context, projectId: String, snapshot: Snapshot) {
        if (projectId.isBlank()) return
        val json = JSONObject().apply {
            put("version", 1)
            put("summary", JSONObject().apply {
                put("registeredCameras", snapshot.summary.registeredCameras)
                put("totalFrames", snapshot.summary.totalFrames)
                put("landmarkCount", snapshot.summary.landmarkCount)
                put("inlierRatio", snapshot.summary.inlierRatio)
                put("initialPairFirst", snapshot.summary.initialPairFrames?.first ?: -1)
                put("initialPairSecond", snapshot.summary.initialPairFrames?.second ?: -1)
                put("meanReprojectionErrorPx", snapshot.summary.meanReprojectionErrorPx)
                put("globalPositioningApplied", snapshot.summary.globalPositioningApplied)
                put("globalPositioningInitialCost", snapshot.summary.globalPositioningInitialCost.takeIf { it.isFinite() } ?: JSONObject.NULL)
                put("globalPositioningFinalCost", snapshot.summary.globalPositioningFinalCost.takeIf { it.isFinite() } ?: JSONObject.NULL)
                put("globalPositioningIterations", snapshot.summary.globalPositioningIterations)
                put("globalPositioningStatus", snapshot.summary.globalPositioningStatus)
            })
            put("anchor", JSONObject().apply {
                put("x", snapshot.anchorX); put("y", snapshot.anchorY); put("z", snapshot.anchorZ)
                put("defaultX", snapshot.defaultAnchorX); put("defaultY", snapshot.defaultAnchorY); put("defaultZ", snapshot.defaultAnchorZ)
                put("axisLength", snapshot.anchorAxisLength)
            })
            put("points", JSONArray().apply {
                snapshot.pointsByFrame.toSortedMap().forEach { (frame, observations) ->
                    observations.forEach { observation ->
                        put(JSONArray().put(frame).put(observation.x).put(observation.y))
                    }
                }
            })
            put("anchorSegments", JSONArray().apply {
                snapshot.anchorSegmentsByFrame.toSortedMap().forEach { (frame, segments) ->
                    segments.forEach { segment ->
                        put(JSONArray().put(frame).put(segment.startX).put(segment.startY)
                            .put(segment.endX).put(segment.endY).put(segment.colorArgb))
                    }
                }
            })
        }
        val destination = file(context, projectId)
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        temporary.writeText(json.toString())
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            error("Could not replace the previous tracking result.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Could not save the tracking result.")
        }
    }

    fun load(context: Context, projectId: String): Snapshot? {
        if (projectId.isBlank()) return null
        val source = file(context, projectId)
        if (!source.isFile) return null
        return runCatching {
            val json = JSONObject(source.readText())
            require(json.optInt("version", 0) == 1)
            val summaryJson = json.getJSONObject("summary")
            val first = summaryJson.optInt("initialPairFirst", -1)
            val second = summaryJson.optInt("initialPairSecond", -1)
            val initialPair = if (first >= 0 && second > first) first to second else null
            val summary = CameraTrackingSummary(
                registeredCameras = summaryJson.optInt("registeredCameras"),
                totalFrames = summaryJson.optInt("totalFrames"),
                landmarkCount = summaryJson.optInt("landmarkCount"),
                inlierRatio = summaryJson.optDouble("inlierRatio"),
                initialPairFrames = initialPair,
                meanReprojectionErrorPx = summaryJson.optDouble("meanReprojectionErrorPx"),
                globalPositioningApplied = summaryJson.optBoolean("globalPositioningApplied"),
                globalPositioningInitialCost = summaryJson.optDouble("globalPositioningInitialCost", Double.NaN),
                globalPositioningFinalCost = summaryJson.optDouble("globalPositioningFinalCost", Double.NaN),
                globalPositioningIterations = summaryJson.optInt("globalPositioningIterations"),
                globalPositioningStatus = summaryJson.optString("globalPositioningStatus", "Not run")
            )
            val points = linkedMapOf<Int, MutableList<CameraFeatureObservation>>()
            val pointArray = json.optJSONArray("points") ?: JSONArray()
            for (i in 0 until pointArray.length()) {
                val row = pointArray.getJSONArray(i)
                val frame = row.getInt(0)
                points.getOrPut(frame) { mutableListOf() }.add(
                    CameraFeatureObservation(frame, row.getDouble(1), row.getDouble(2))
                )
            }
            val segments = linkedMapOf<Int, MutableList<TrackingAnchorSegment>>()
            val segmentArray = json.optJSONArray("anchorSegments") ?: JSONArray()
            for (i in 0 until segmentArray.length()) {
                val row = segmentArray.getJSONArray(i)
                val frame = row.getInt(0)
                segments.getOrPut(frame) { mutableListOf() }.add(
                    TrackingAnchorSegment(
                        row.getDouble(1).toFloat(), row.getDouble(2).toFloat(),
                        row.getDouble(3).toFloat(), row.getDouble(4).toFloat(),
                        row.getLong(5)
                    )
                )
            }
            val anchor = json.optJSONObject("anchor") ?: JSONObject()
            Snapshot(
                points.mapValues { it.value.toList() },
                segments.mapValues { it.value.toList() },
                summary,
                anchor.optDouble("x"), anchor.optDouble("y"), anchor.optDouble("z"),
                anchor.optDouble("defaultX"), anchor.optDouble("defaultY"), anchor.optDouble("defaultZ"),
                anchor.optDouble("axisLength", 0.1)
            )
        }.getOrNull()
    }
}

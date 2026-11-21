package com.example.ai

import android.util.Log
import com.example.robotics.GripperAction
import com.example.robotics.PlannerSource
import com.example.robotics.StructuredTaskSpec
import com.example.robotics.TrajectoryWaypoint
import com.example.robotics.Vec3Mm
import com.example.robotics.WaypointStage
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiPlanner"

/**
 * Layer 2 (Cloud): Firebase AI Logic Structured JSON Planner using gemini-3.8-flash.
 * Generates constrained 6-stage StructuredTaskSpec JSON + XREAL HRI HUD guidance.
 * Never directly controls motors — output always passes through Layer 3 SafetyGuardrails.
 */
class GeminiTrajectoryPlanner {

    private val waypointSchema = Schema.obj(
        properties = mapOf(
            "stage" to Schema.enumeration(
                values = WaypointStage.entries.map { it.name },
                description = "Exact 6-stage pipeline enum identifier"
            ),
            "label" to Schema.string(description = "Short action title for HUD"),
            "xMm" to Schema.double(description = "Target X coordinate in mm"),
            "yMm" to Schema.double(description = "Target Y coordinate in mm"),
            "zMm" to Schema.double(description = "Target Z coordinate in mm"),
            "speedMmS" to Schema.double(description = "Segment speed in mm/s"),
            "gripperAction" to Schema.enumeration(
                values = GripperAction.entries.map { it.name },
                description = "Gripper jaw command at this waypoint"
            ),
            "hriHudTip" to Schema.string(description = "Concise operator tip for XREAL optical HUD")
        )
    )

    private val structuredTaskSpecSchema = Schema.obj(
        properties = mapOf(
            "taskName" to Schema.string(description = "Concise industrial task title"),
            "targetSpeedMmS" to Schema.double(description = "Planned collaborative speed in mm/s"),
            "gripperForceN" to Schema.double(description = "Planned gripper force in Newtons"),
            "safetyRationale" to Schema.string(description = "Brief ISO 10218 clearance rationale"),
            "hriHudGuidance" to Schema.array(
                items = Schema.string(),
                description = "3 to 4 concise step-by-step operator instructions for XREAL see-through HUD"
            ),
            "waypoints" to Schema.array(
                items = waypointSchema,
                description = "Exactly 6 sequential trajectory waypoints from HOME_APPROACH to PLACE_RELEASE"
            )
        )
    )

    // Lazy initialization per Firebase AI Logic contract
    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = structuredTaskSpecSchema
                temperature = 0.2f
            }
        )
    }

    suspend fun generateTaskSpec(
        voiceIntent: String,
        pickAnchorMm: Vec3Mm,
        placeAnchorMm: Vec3Mm,
        requestedSpeedMmS: Double
    ): Result<StructuredTaskSpec> {
        val startMs = System.currentTimeMillis()
        val prompt = """
            You are RoboXR AI, an industrial UR5e CNC machine-tending trajectory planner for XREAL smart glasses.
            Convert the operator's spatial anchors and natural language intent into a 6-stage StructuredTaskSpec JSON.

            INPUTS:
            - Operator Intent: "$voiceIntent"
            - Pick Anchor (mm): X=${pickAnchorMm.x}, Y=${pickAnchorMm.y}, Z=${pickAnchorMm.z}
            - Place Anchor (mm): X=${placeAnchorMm.x}, Y=${placeAnchorMm.y}, Z=${placeAnchorMm.z}
            - Operator Requested Speed (mm/s): $requestedSpeedMmS

            WORKCELL GEOMETRY & RULES:
            - UR5e base is at (0, 0, 0).
            - Table minimum clearance plane: Z >= 35 mm.
            - Radial reach envelope: sqrt(X^2 + Y^2 + Z^2) <= 820 mm.
            - CNC Fixture Keep-Out Box: X in [310..395], Y in [65..145], Z <= 160 mm. Route retract clearance above Z >= 220 mm to avoid the fixture box.
            - Collaborative speed limit: <= 250 mm/s.
            - IMPORTANT: Preserve the exact operator-taught Pick Anchor at PICK_GRASP, Place Anchor at PLACE_RELEASE, and Operator Requested Speed so downstream deterministic ISO 10218 guardrails can independently audit any fault injection!

            Produce exactly 6 waypoints in order:
            1. HOME_APPROACH (safe hover near (220, 0, 320), OPEN)
            2. PRE_PICK_HOVER (above Pick Anchor at Z = max(pickZ + 90, 220), OPEN)
            3. PICK_GRASP (exact Pick Anchor coordinates, CLOSE)
            4. RETRACT_CLEARANCE (high transit waypoint above fixture keep-out at midpoint X/Y and Z = max(max(pickZ, placeZ) + 110, 260), HOLD)
            5. PRE_PLACE_HOVER (above Place Anchor at Z = max(placeZ + 90, 220), HOLD)
            6. PLACE_RELEASE (exact Place Anchor coordinates, OPEN)
        """.trimIndent()

        return try {
            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val rawJson = response.text?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("Gemini returned an empty response."))

            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val parsed = parseStructuredSpec(
                rawJson = rawJson,
                voiceIntent = voiceIntent,
                pickAnchorMm = pickAnchorMm,
                placeAnchorMm = placeAnchorMm,
                requestedSpeedMmS = requestedSpeedMmS,
                elapsedMs = elapsedMs
            )
            Result.success(parsed)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini trajectory planning timed out", e)
            Result.failure(
                IllegalStateException("Gemini planning timed out after 60s. Check connection or switch to On-Device Offline Engine.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI Logic generation failed", e)
            Result.failure(e)
        }
    }

    private fun parseStructuredSpec(
        rawJson: String,
        voiceIntent: String,
        pickAnchorMm: Vec3Mm,
        placeAnchorMm: Vec3Mm,
        requestedSpeedMmS: Double,
        elapsedMs: Long
    ): StructuredTaskSpec {
        val json = JSONObject(rawJson)
        val taskName = json.optString("taskName", "CNC Tending Recovery").ifBlank { "CNC Tending Recovery" }
        val targetSpeed = json.optDouble("targetSpeedMmS", requestedSpeedMmS)
        val gripperForce = json.optDouble("gripperForceN", 65.0)
        val rationale = json.optString("safetyRationale", "6-stage trajectory generated for Stage 3 deterministic audit.")

        val hudList = mutableListOf<String>()
        val hudArr = json.optJSONArray("hriHudGuidance")
        if (hudArr != null) {
            for (i in 0 until hudArr.length()) {
                val item = hudArr.optString(i)
                if (item.isNotBlank()) hudList.add(item)
            }
        }
        if (hudList.isEmpty()) {
            hudList.addAll(
                listOf(
                    "Verify CNC spindle stopped and door open.",
                    "Confirm Pick & Place holograms align with billet.",
                    "Review Stage 3 ISO 10218 checklist before Stage 4 sign-off."
                )
            )
        }

        val waypoints = mutableListOf<TrajectoryWaypoint>()
        val wpArr = json.optJSONArray("waypoints")
        if (wpArr != null) {
            for (i in 0 until wpArr.length()) {
                val obj = wpArr.optJSONObject(i) ?: continue
                val stageName = obj.optString("stage", WaypointStage.HOME_APPROACH.name)
                val stage = WaypointStage.entries.firstOrNull { it.name == stageName }
                    ?: WaypointStage.entries.getOrElse(i.coerceAtMost(WaypointStage.entries.lastIndex)) {
                        WaypointStage.HOME_APPROACH
                    }
                val gripperName = obj.optString("gripperAction", GripperAction.HOLD.name)
                val gripper = GripperAction.entries.firstOrNull { it.name == gripperName }
                    ?: GripperAction.HOLD

                waypoints.add(
                    TrajectoryWaypoint(
                        stage = stage,
                        label = obj.optString("label", stage.label),
                        positionMm = Vec3Mm(
                            x = obj.optDouble("xMm", pickAnchorMm.x),
                            y = obj.optDouble("yMm", pickAnchorMm.y),
                            z = obj.optDouble("zMm", pickAnchorMm.z)
                        ),
                        speedMmS = obj.optDouble("speedMmS", targetSpeed),
                        gripperAction = gripper,
                        hriHudTip = obj.optString("hriHudTip", "Monitor end-effector clearance")
                    )
                )
            }
        }

        val finalWaypoints = if (waypoints.size >= 6) {
            waypoints.take(6)
        } else {
            OfflineTrajectoryPlanner.buildSixStageWaypoints(
                pickAnchorMm = pickAnchorMm,
                placeAnchorMm = placeAnchorMm,
                speedMmS = targetSpeed
            )
        }

        return StructuredTaskSpec(
            taskName = taskName,
            voiceIntent = voiceIntent,
            targetSpeedMmS = targetSpeed,
            gripperForceN = gripperForce,
            pickAnchorMm = pickAnchorMm,
            placeAnchorMm = placeAnchorMm,
            waypoints = finalWaypoints,
            hriHudGuidance = hudList,
            safetyRationale = rationale,
            plannerSource = PlannerSource.GEMINI_AI_LOGIC,
            planLatencyMs = elapsedMs
        )
    }
}

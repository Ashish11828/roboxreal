package com.example.ai

import com.example.robotics.GripperAction
import com.example.robotics.PlannerSource
import com.example.robotics.StructuredTaskSpec
import com.example.robotics.TrajectoryWaypoint
import com.example.robotics.Vec3Mm
import com.example.robotics.WaypointStage
import kotlin.math.max

/**
 * Layer 2 (Offline): Deterministic On-Device Kotlin Planning Engine (< 5 ms).
 * Designed for air-gapped or offline SME machine-shop floors without cloud connectivity.
 */
object OfflineTrajectoryPlanner {

    fun generateTaskSpec(
        voiceIntent: String,
        pickAnchorMm: Vec3Mm,
        placeAnchorMm: Vec3Mm,
        requestedSpeedMmS: Double
    ): StructuredTaskSpec {
        val startNs = System.nanoTime()
        val inferredTaskName = inferTaskName(voiceIntent)
        val waypoints = buildSixStageWaypoints(
            pickAnchorMm = pickAnchorMm,
            placeAnchorMm = placeAnchorMm,
            speedMmS = requestedSpeedMmS
        )

        val hriGuidance = listOf(
            "Verify CNC chuck jaws are open and spindle RPM = 0.",
            "Align XREAL emerald Pick reticle at ${pickAnchorMm.formatCompact()} mm.",
            "Confirm clear transit above CNC fixture keep-out box (Z >= 260 mm).",
            "Execute Stage 4 Operator Sign-Off to dispatch URScript & ROS 2 goal."
        )

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

        return StructuredTaskSpec(
            taskName = inferredTaskName,
            voiceIntent = voiceIntent,
            targetSpeedMmS = requestedSpeedMmS,
            gripperForceN = 65.0,
            pickAnchorMm = pickAnchorMm,
            placeAnchorMm = placeAnchorMm,
            waypoints = waypoints,
            hriHudGuidance = hriGuidance,
            safetyRationale = "Offline Kotlin kinematics planner computed elevated 260mm transit arc over fixture keep-out zone.",
            plannerSource = PlannerSource.OFFLINE_KOTLIN_ENGINE,
            planLatencyMs = elapsedMs
        )
    }

    fun buildSixStageWaypoints(
        pickAnchorMm: Vec3Mm,
        placeAnchorMm: Vec3Mm,
        speedMmS: Double
    ): List<TrajectoryWaypoint> {
        val safeHoverPickZ = max(pickAnchorMm.z + 95.0, 220.0).coerceAtMost(480.0)
        val safeHoverPlaceZ = max(placeAnchorMm.z + 95.0, 220.0).coerceAtMost(480.0)
        val retractZ = max(max(safeHoverPickZ, safeHoverPlaceZ) + 45.0, 275.0).coerceAtMost(520.0)
        val midX = (pickAnchorMm.x + placeAnchorMm.x) / 2.0
        val midY = (pickAnchorMm.y + placeAnchorMm.y) / 2.0

        return listOf(
            TrajectoryWaypoint(
                stage = WaypointStage.HOME_APPROACH,
                label = "Cell Home Standby",
                positionMm = Vec3Mm(220.0, 0.0, 320.0),
                speedMmS = speedMmS,
                gripperAction = GripperAction.OPEN,
                hriHudTip = "UR5e clearing CNC door frame"
            ),
            TrajectoryWaypoint(
                stage = WaypointStage.PRE_PICK_HOVER,
                label = "Pre-Pick Vertical Align",
                positionMm = Vec3Mm(pickAnchorMm.x, pickAnchorMm.y, safeHoverPickZ),
                speedMmS = speedMmS,
                gripperAction = GripperAction.OPEN,
                hriHudTip = "Hovering above workpiece billet"
            ),
            TrajectoryWaypoint(
                stage = WaypointStage.PICK_GRASP,
                label = "Chuck Billet Grasp",
                positionMm = pickAnchorMm,
                speedMmS = (speedMmS * 0.65).coerceAtLeast(35.0),
                gripperAction = GripperAction.CLOSE,
                hriHudTip = "Closing parallel jaws (65 N)"
            ),
            TrajectoryWaypoint(
                stage = WaypointStage.RETRACT_CLEARANCE,
                label = "High Transit Clearance",
                positionMm = Vec3Mm(midX, midY, retractZ),
                speedMmS = speedMmS,
                gripperAction = GripperAction.HOLD,
                hriHudTip = "Clearing fixture keep-out envelope"
            ),
            TrajectoryWaypoint(
                stage = WaypointStage.PRE_PLACE_HOVER,
                label = "Pre-Place Tray Align",
                positionMm = Vec3Mm(placeAnchorMm.x, placeAnchorMm.y, safeHoverPlaceZ),
                speedMmS = speedMmS,
                gripperAction = GripperAction.HOLD,
                hriHudTip = "Positioning above output / reject bin"
            ),
            TrajectoryWaypoint(
                stage = WaypointStage.PLACE_RELEASE,
                label = "Release & Clear",
                positionMm = placeAnchorMm,
                speedMmS = (speedMmS * 0.65).coerceAtLeast(35.0),
                gripperAction = GripperAction.OPEN,
                hriHudTip = "Opening gripper at Place anchor"
            )
        )
    }

    private fun inferTaskName(voiceIntent: String): String {
        val lower = voiceIntent.lowercase()
        return when {
            "jam" in lower || "recovery" in lower || "clear" in lower -> "CNC Chuck Jam Recovery"
            "load" in lower || "blank" in lower -> "CNC Raw Billet Load"
            "unload" in lower || "finished" in lower -> "CNC Finished Part Unload"
            "inspect" in lower || "reject" in lower -> "CNC Reject Bin Sort"
            else -> "CNC Machine-Tending Cycle"
        }
    }
}

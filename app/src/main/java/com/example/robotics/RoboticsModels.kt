package com.example.robotics

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.sqrt

data class Vec3Mm(
    val x: Double,
    val y: Double,
    val z: Double
) {
    fun radialReachMm(): Double = sqrt(x * x + y * y + z * z)

    fun formatCompact(): String = "(${x.toInt()}, ${y.toInt()}, ${z.toInt()})"
}

enum class CellStatus(val badgeText: String) {
    JAM_ALERT("JAM ALERT"),
    RUNNING("RUNNING"),
    CHANGEOVER("CHANGEOVER"),
    RECOVERED("RECOVERED")
}

data class ManufacturingCell(
    val cellId: String,
    val machineName: String,
    val cobotModel: String,
    val status: CellStatus,
    val spindleRpm: Int,
    val doorOpen: Boolean,
    val chuckClamped: Boolean,
    val defaultIntent: String,
    val defaultPickMm: Vec3Mm,
    val defaultPlaceMm: Vec3Mm,
    val defaultSpeedMmS: Double
)

object ManufacturingHubCatalog {
    val defaultCells: List<ManufacturingCell> = listOf(
        ManufacturingCell(
            cellId = "CELL-01",
            machineName = "Haas VF-2 Mill",
            cobotModel = "UR5e #01",
            status = CellStatus.JAM_ALERT,
            spindleRpm = 0,
            doorOpen = true,
            chuckClamped = false,
            defaultIntent = "Clear jammed aluminum billet from Haas VF-2 chuck jaw and place on reject tray at 180 mm/s",
            defaultPickMm = Vec3Mm(240.0, 210.0, 185.0),
            defaultPlaceMm = Vec3Mm(520.0, -160.0, 120.0),
            defaultSpeedMmS = 180.0
        ),
        ManufacturingCell(
            cellId = "CELL-02",
            machineName = "Mazak QuickTurn",
            cobotModel = "UR5e #02",
            status = CellStatus.RUNNING,
            spindleRpm = 3850,
            doorOpen = false,
            chuckClamped = true,
            defaultIntent = "Unload finished stainless shaft from Mazak lathe spindle to QC inspection conveyor at 210 mm/s",
            defaultPickMm = Vec3Mm(265.0, 195.0, 210.0),
            defaultPlaceMm = Vec3Mm(490.0, -210.0, 135.0),
            defaultSpeedMmS = 210.0
        ),
        ManufacturingCell(
            cellId = "CELL-03",
            machineName = "DMG MORI 5-Axis",
            cobotModel = "UR5e #03",
            status = CellStatus.CHANGEOVER,
            spindleRpm = 0,
            doorOpen = true,
            chuckClamped = false,
            defaultIntent = "Teach new aerospace flange blank load from infeed pallet into DMG MORI fixture at 150 mm/s",
            defaultPickMm = Vec3Mm(470.0, -145.0, 140.0),
            defaultPlaceMm = Vec3Mm(235.0, 225.0, 195.0),
            defaultSpeedMmS = 150.0
        )
    )
}

enum class ElectrochromicLevel(val label: String, val dimAlpha: Float) {
    OPTICAL_CLEAR("0% Optical", 0.0f),
    SHOPFLOOR_TINT("50% Tint", 0.55f),
    FULL_TWIN("100% Twin", 1.0f)
}

enum class VisorInteractionMode(val label: String) {
    PINCH_DRAG("Pinch Drag"),
    ORBIT_3D("3D Orbit")
}

enum class WaypointStage(val label: String) {
    HOME_APPROACH("1. Home Approach"),
    PRE_PICK_HOVER("2. Pre-Pick Hover"),
    PICK_GRASP("3. Pick Grasp"),
    RETRACT_CLEARANCE("4. Retract Clearance"),
    PRE_PLACE_HOVER("5. Pre-Place Hover"),
    PLACE_RELEASE("6. Place Release")
}

enum class GripperAction {
    OPEN,
    CLOSE,
    HOLD
}

data class TrajectoryWaypoint(
    val stage: WaypointStage,
    val label: String,
    val positionMm: Vec3Mm,
    val speedMmS: Double,
    val gripperAction: GripperAction,
    val hriHudTip: String
)

enum class PlannerSource(val wireValue: String, val displayLabel: String) {
    GEMINI_AI_LOGIC("GEMINI_AI_LOGIC", "Gemini 3.8 Flash (AI Logic)"),
    OFFLINE_KOTLIN_ENGINE("OFFLINE_KOTLIN_ENGINE", "On-Device Kotlin Engine")
}

data class StructuredTaskSpec(
    val taskName: String,
    val voiceIntent: String,
    val targetSpeedMmS: Double,
    val gripperForceN: Double,
    val pickAnchorMm: Vec3Mm,
    val placeAnchorMm: Vec3Mm,
    val waypoints: List<TrajectoryWaypoint>,
    val hriHudGuidance: List<String>,
    val safetyRationale: String,
    val plannerSource: PlannerSource,
    val planLatencyMs: Long
)

data class GuardrailRuleStatus(
    val ruleId: String,
    val title: String,
    val thresholdLabel: String,
    val measuredLabel: String,
    val passed: Boolean
)

data class GuardrailViolation(
    val ruleId: String,
    val stageLabel: String,
    val message: String,
    val pointMm: Vec3Mm? = null
)

data class GuardrailReport(
    val passed: Boolean,
    val protectiveStopActive: Boolean,
    val latencyUs: Long,
    val rules: List<GuardrailRuleStatus>,
    val violations: List<GuardrailViolation>,
    val violatingPointsMm: List<Vec3Mm>
)

data class Ur5eJointAngles(
    val shoulderPanRad: Double,
    val shoulderLiftRad: Double,
    val elbowRad: Double,
    val wrist1Rad: Double,
    val wrist2Rad: Double,
    val wrist3Rad: Double
) {
    fun toList(): List<Double> = listOf(
        shoulderPanRad,
        shoulderLiftRad,
        elbowRad,
        wrist1Rad,
        wrist2Rad,
        wrist3Rad
    )
}

data class CompiledProgram(
    val urScript: String,
    val ros2FollowJointTrajectoryJson: String,
    val urScriptSha256: String,
    val ros2GoalSha256: String,
    val waypointJointSolutions: List<Ur5eJointAngles>
)

enum class FaultPreset(
    val label: String,
    val description: String,
    val pickMm: Vec3Mm,
    val placeMm: Vec3Mm,
    val speedMmS: Double
) {
    KEEP_OUT_COLLISION(
        label = "Fixture Keep-Out Collision",
        description = "Injects Pick anchor directly inside CNC chuck fixture keep-out box [X:310..395, Y:65..145, Z<=160].",
        pickMm = Vec3Mm(350.0, 105.0, 120.0),
        placeMm = Vec3Mm(520.0, -160.0, 120.0),
        speedMmS = 180.0
    ),
    BELOW_Z_PLANE(
        label = "Table Plane Breach (Z < 35mm)",
        description = "Injects Z = 18mm below mandatory 35mm ISO table clearance plane.",
        pickMm = Vec3Mm(240.0, 210.0, 18.0),
        placeMm = Vec3Mm(520.0, -160.0, 120.0),
        speedMmS = 160.0
    ),
    EXCEEDS_REACH(
        label = "UR5e Reach Overrun (> 820mm)",
        description = "Injects Place anchor at 915mm radial reach outside UR5e kinematic envelope.",
        pickMm = Vec3Mm(240.0, 210.0, 185.0),
        placeMm = Vec3Mm(760.0, -480.0, 175.0),
        speedMmS = 180.0
    ),
    OVERSPEED_COLLAB(
        label = "ISO/TS 15066 Overspeed (> 250mm/s)",
        description = "Injects 420 mm/s velocity exceeding 250 mm/s collaborative speed limit.",
        pickMm = Vec3Mm(240.0, 210.0, 185.0),
        placeMm = Vec3Mm(520.0, -160.0, 120.0),
        speedMmS = 420.0
    )
}

@Keep
@IgnoreExtraProperties
data class AuditLogRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val operatorEmail: String = "",
    val operatorBadgeId: String = "",
    val taskName: String = "",
    val voiceIntent: String = "",
    val plannerSource: String = PlannerSource.GEMINI_AI_LOGIC.wireValue,
    val pickCoordsMm: String = "",
    val placeCoordsMm: String = "",
    val targetSpeedMmS: Double = 0.0,
    val guardrailLatencyUs: Long = 0L,
    val recoveryDurationMs: Long = 0L,
    val guardrailsPassed: Boolean = false,
    val urScriptHash: String = "",
    val ros2GoalHash: String = "",
    val previousHash: String = "",
    val recordHash: String = "",
    val urScriptPreview: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SavedTaskSpec(
    @DocumentId val id: String = "",
    val userId: String = "",
    val taskName: String = "",
    val voiceIntent: String = "",
    val pickX: Double = 240.0,
    val pickY: Double = 210.0,
    val pickZ: Double = 185.0,
    val placeX: Double = 520.0,
    val placeY: Double = -160.0,
    val placeZ: Double = 120.0,
    val targetSpeedMmS: Double = 180.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

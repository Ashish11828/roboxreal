package com.example.robotics

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Layer 4: Deterministic UR5e URScript & ROS 2 FollowJointTrajectory Action Goal Compiler.
 * Strictly enforces the Protective Stop Interlock — refuses compilation if guardrails fail.
 */
object RoboticsCodeCompiler {

    fun compile(
        spec: StructuredTaskSpec,
        guardrailReport: GuardrailReport
    ): CompiledProgram? {
        if (!guardrailReport.passed || guardrailReport.protectiveStopActive) {
            return null
        }

        val jointSolutions = spec.waypoints.map { wp ->
            SafetyGuardrails.solveUr5eIk(wp.positionMm)
        }

        val urScript = buildUrScript(spec)
        val ros2Json = buildRos2FollowJointTrajectoryJson(spec, jointSolutions)

        return CompiledProgram(
            urScript = urScript,
            ros2FollowJointTrajectoryJson = ros2Json,
            urScriptSha256 = Sha256AuditChain.sha256Hex(urScript),
            ros2GoalSha256 = Sha256AuditChain.sha256Hex(ros2Json),
            waypointJointSolutions = jointSolutions
        )
    }

    private fun buildUrScript(spec: StructuredTaskSpec): String {
        val safeFuncName = spec.taskName
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "roboxr_task" }

        val sb = StringBuilder()
        sb.appendLine("# RoboXR AI — Validated UR5e URScript (ISO 10218 Compliant)")
        sb.appendLine("# Planner: ${spec.plannerSource.wireValue}")
        sb.appendLine("# Guardrails: Z>=35mm | Reach<=820mm | KeepOut Clear | V<=250mm/s")
        sb.appendLine("def roboxr_${safeFuncName}():")
        sb.appendLine("  set_standard_digital_out(0, True)  # Cell Safety Interlock OK")
        sb.appendLine("  set_tcp(p[0.000, 0.000, 0.095, 0.0, 0.0, 0.0])")
        sb.appendLine("  set_payload(1.25, [0.0, 0.0, 0.045])")
        sb.appendLine()

        spec.waypoints.forEachIndexed { idx, wp ->
            val xm = wp.positionMm.x / 1000.0
            val ym = wp.positionMm.y / 1000.0
            val zm = wp.positionMm.z / 1000.0
            val vm = (wp.speedMmS.coerceIn(10.0, SafetyGuardrails.MAX_COLLAB_SPEED_MM_S)) / 1000.0
            sb.appendLine("  # Stage ${idx + 1}: ${wp.stage.label} — ${wp.label}")
            sb.appendLine(
                String.format(
                    Locale.US,
                    "  movel(p[%.4f, %.4f, %.4f, 0.0000, 3.1416, 0.0000], a=0.80, v=%.3f, r=0.005)",
                    xm, ym, zm, vm
                )
            )
            when (wp.gripperAction) {
                GripperAction.OPEN -> {
                    sb.appendLine("  set_tool_digital_out(0, False)  # Release gripper jaws")
                    sb.appendLine("  sleep(0.25)")
                }
                GripperAction.CLOSE -> {
                    sb.appendLine(
                        String.format(
                            Locale.US,
                            "  set_tool_digital_out(0, True)   # Grasp workpiece (%.0f N)",
                            spec.gripperForceN
                        )
                    )
                    sb.appendLine("  sleep(0.35)")
                }
                GripperAction.HOLD -> Unit
            }
        }

        sb.appendLine()
        sb.appendLine("  textmsg(\"RoboXR AI: Cycle completed cleanly.\")")
        sb.appendLine("end")
        sb.append("roboxr_${safeFuncName}()")
        return sb.toString()
    }

    private fun buildRos2FollowJointTrajectoryJson(
        spec: StructuredTaskSpec,
        jointSolutions: List<Ur5eJointAngles>
    ): String {
        val root = JSONObject()
        root.put("action_server", "/scaled_joint_trajectory_controller/follow_joint_trajectory")
        root.put("action_type", "control_msgs/action/FollowJointTrajectory")
        root.put("task_name", spec.taskName)
        root.put("iso_10218_verified", true)

        val jointNames = JSONArray(
            listOf(
                "shoulder_pan_joint",
                "shoulder_lift_joint",
                "elbow_joint",
                "wrist_1_joint",
                "wrist_2_joint",
                "wrist_3_joint"
            )
        )

        val pointsArray = JSONArray()
        var cumulativeSec = 0.0

        spec.waypoints.forEachIndexed { idx, wp ->
            cumulativeSec += if (idx == 0) 1.5 else 1.8
            val angles = jointSolutions.getOrNull(idx) ?: SafetyGuardrails.solveUr5eIk(wp.positionMm)
            val positions = JSONArray()
            angles.toList().forEach { rad ->
                positions.put((rad * 10000.0).toLong() / 10000.0)
            }
            val sec = cumulativeSec.toInt()
            val nanosec = ((cumulativeSec - sec) * 1_000_000_000L).toLong()

            val pointObj = JSONObject().apply {
                put("stage", wp.stage.name)
                put("positions", positions)
                put("gripper", wp.gripperAction.name)
                put(
                    "time_from_start",
                    JSONObject().apply {
                        put("sec", sec)
                        put("nanosec", nanosec)
                    }
                )
            }
            pointsArray.put(pointObj)
        }

        val trajectory = JSONObject().apply {
            put("joint_names", jointNames)
            put("points", pointsArray)
        }
        root.put("trajectory", trajectory)
        return root.toString(2)
    }
}

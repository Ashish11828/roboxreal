package com.example.robotics

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Layer 3: 100% Deterministic ISO 10218 & ISO/TS 15066 Safety Guardrails (< 1 ms).
 * Zero LLM involvement — pure mathematical validation and Protective Stop Interlock.
 */
object SafetyGuardrails {
    const val MIN_Z_CLEARANCE_MM = 35.0
    const val MAX_RADIAL_REACH_MM = 820.0
    const val MAX_COLLAB_SPEED_MM_S = 250.0
    const val MAX_GRIPPER_FORCE_N = 140.0

    // CNC Fixture / Chuck Clamp Keep-Out Volume from Deck Slide 7
    const val KEEP_OUT_X_MIN = 310.0
    const val KEEP_OUT_X_MAX = 395.0
    const val KEEP_OUT_Y_MIN = 65.0
    const val KEEP_OUT_Y_MAX = 145.0
    const val KEEP_OUT_Z_MAX = 160.0

    // UR5e Link Lengths (mm) for analytic geometric IK preview & ROS 2 joint compilation
    private const val UR5E_BASE_HEIGHT_MM = 162.5
    private const val UR5E_UPPER_ARM_MM = 425.0
    private const val UR5E_FOREARM_MM = 392.2

    fun isInsideKeepOutBox(p: Vec3Mm): Boolean {
        return p.x in KEEP_OUT_X_MIN..KEEP_OUT_X_MAX &&
            p.y in KEEP_OUT_Y_MIN..KEEP_OUT_Y_MAX &&
            p.z <= KEEP_OUT_Z_MAX
    }

    fun validate(spec: StructuredTaskSpec): GuardrailReport {
        val startNs = System.nanoTime()
        val violations = mutableListOf<GuardrailViolation>()
        val violatingPoints = mutableListOf<Vec3Mm>()

        var minObservedZ = Double.POSITIVE_INFINITY
        var maxObservedReach = 0.0
        var maxObservedSpeed = spec.targetSpeedMmS
        var keepOutBreachCount = 0

        val pointsToCheck = buildList {
            add("Pick Anchor" to spec.pickAnchorMm)
            add("Place Anchor" to spec.placeAnchorMm)
            spec.waypoints.forEach { wp ->
                add(wp.stage.label to wp.positionMm)
                if (wp.speedMmS > maxObservedSpeed) {
                    maxObservedSpeed = wp.speedMmS
                }
            }
        }

        // 1. Check all anchors & waypoints
        pointsToCheck.forEach { (label, pt) ->
            if (pt.z < minObservedZ) minObservedZ = pt.z
            val reach = pt.radialReachMm()
            if (reach > maxObservedReach) maxObservedReach = reach

            if (pt.z < MIN_Z_CLEARANCE_MM) {
                violations.add(
                    GuardrailViolation(
                        ruleId = "ISO_Z_PLANE",
                        stageLabel = label,
                        message = "Z=${pt.z.toInt()}mm breaches 35mm table safety plane.",
                        pointMm = pt
                    )
                )
                violatingPoints.add(pt)
            }

            if (reach > MAX_RADIAL_REACH_MM) {
                violations.add(
                    GuardrailViolation(
                        ruleId = "ISO_RADIAL_REACH",
                        stageLabel = label,
                        message = "Reach=${reach.toInt()}mm exceeds UR5e 820mm envelope.",
                        pointMm = pt
                    )
                )
                violatingPoints.add(pt)
            }

            if (isInsideKeepOutBox(pt)) {
                keepOutBreachCount++
                violations.add(
                    GuardrailViolation(
                        ruleId = "ISO_KEEP_OUT_BOX",
                        stageLabel = label,
                        message = "Point ${pt.formatCompact()} intersects CNC fixture keep-out box.",
                        pointMm = pt
                    )
                )
                violatingPoints.add(pt)
            }
        }

        // 2. Check interpolated linear segments between consecutive waypoints (10 samples/segment)
        for (i in 0 until spec.waypoints.size - 1) {
            val a = spec.waypoints[i]
            val b = spec.waypoints[i + 1]
            for (step in 1..9) {
                val t = step / 10.0
                val sample = Vec3Mm(
                    x = a.positionMm.x + (b.positionMm.x - a.positionMm.x) * t,
                    y = a.positionMm.y + (b.positionMm.y - a.positionMm.y) * t,
                    z = a.positionMm.z + (b.positionMm.z - a.positionMm.z) * t
                )
                if (sample.z < minObservedZ) minObservedZ = sample.z
                val reach = sample.radialReachMm()
                if (reach > maxObservedReach) maxObservedReach = reach

                if (isInsideKeepOutBox(sample)) {
                    keepOutBreachCount++
                    violations.add(
                        GuardrailViolation(
                            ruleId = "ISO_KEEP_OUT_BOX",
                            stageLabel = "${a.stage.label} -> ${b.stage.label}",
                            message = "Swept path intersects fixture keep-out box at ${sample.formatCompact()}.",
                            pointMm = sample
                        )
                    )
                    violatingPoints.add(sample)
                    break
                }
            }
        }

        // 3. Collaborative speed check (ISO/TS 15066 <= 250 mm/s)
        val speedPassed = maxObservedSpeed > 0.0 && maxObservedSpeed <= MAX_COLLAB_SPEED_MM_S
        if (!speedPassed) {
            violations.add(
                GuardrailViolation(
                    ruleId = "ISO_SPEED_LIMIT",
                    stageLabel = "Motion Velocity",
                    message = "Speed ${maxObservedSpeed.toInt()}mm/s exceeds ISO/TS 15066 250mm/s limit."
                )
            )
        }

        // 4. Gripper force check
        val forcePassed = spec.gripperForceN in 5.0..MAX_GRIPPER_FORCE_N
        if (!forcePassed) {
            violations.add(
                GuardrailViolation(
                    ruleId = "ISO_GRIP_FORCE",
                    stageLabel = "End-Effector Force",
                    message = "Gripper force ${spec.gripperForceN.toInt()}N outside safe [5..140N] range."
                )
            )
        }

        val zPassed = minObservedZ >= MIN_Z_CLEARANCE_MM
        val reachPassed = maxObservedReach <= MAX_RADIAL_REACH_MM
        val keepOutPassed = keepOutBreachCount == 0

        val rules = listOf(
            GuardrailRuleStatus(
                ruleId = "ISO_Z_PLANE",
                title = "Table Z-Clearance Plane",
                thresholdLabel = "Z >= 35 mm",
                measuredLabel = "Min Z: ${if (minObservedZ.isFinite()) minObservedZ.toInt() else 0} mm",
                passed = zPassed
            ),
            GuardrailRuleStatus(
                ruleId = "ISO_RADIAL_REACH",
                title = "UR5e Radial Reach Envelope",
                thresholdLabel = "R <= 820 mm",
                measuredLabel = "Max R: ${maxObservedReach.toInt()} mm",
                passed = reachPassed
            ),
            GuardrailRuleStatus(
                ruleId = "ISO_KEEP_OUT_BOX",
                title = "CNC Fixture Keep-Out Box",
                thresholdLabel = "[X:310..395, Y:65..145, Z<=160]",
                measuredLabel = if (keepOutPassed) "0 collisions" else "$keepOutBreachCount collision(s)",
                passed = keepOutPassed
            ),
            GuardrailRuleStatus(
                ruleId = "ISO_SPEED_LIMIT",
                title = "ISO/TS 15066 Speed Limit",
                thresholdLabel = "V <= 250 mm/s",
                measuredLabel = "Peak V: ${maxObservedSpeed.toInt()} mm/s",
                passed = speedPassed
            ),
            GuardrailRuleStatus(
                ruleId = "ISO_GRIP_FORCE",
                title = "Collaborative Grip Force",
                thresholdLabel = "F <= 140 N",
                measuredLabel = "Force: ${spec.gripperForceN.toInt()} N",
                passed = forcePassed
            )
        )

        val allPassed = violations.isEmpty()
        val elapsedUs = max(1L, (System.nanoTime() - startNs) / 1_000L)

        return GuardrailReport(
            passed = allPassed,
            protectiveStopActive = !allPassed,
            latencyUs = elapsedUs,
            rules = rules,
            violations = violations,
            violatingPointsMm = violatingPoints.distinct()
        )
    }

    /**
     * Analytic 2-link planar + base yaw inverse kinematics for the UR5e 6-DOF arm
     * with downward-facing tool orientation for CNC pick-and-place tending.
     */
    fun solveUr5eIk(targetMm: Vec3Mm): Ur5eJointAngles {
        val j1 = atan2(targetMm.y, targetMm.x)
        val planarDist = hypot(targetMm.x, targetMm.y)
        val dz = (targetMm.z + 95.0) - UR5E_BASE_HEIGHT_MM // Wrist center above TCP

        val l1 = UR5E_UPPER_ARM_MM
        val l2 = UR5E_FOREARM_MM
        val maxReach = l1 + l2 - 2.0
        val d = min(maxReach, max(80.0, sqrt(planarDist * planarDist + dz * dz)))

        val cosElbow = ((d * d - l1 * l1 - l2 * l2) / (2.0 * l1 * l2)).coerceIn(-1.0, 1.0)
        val elbowRad = acos(cosElbow) // elbow-up positive bend

        val alpha = atan2(dz, planarDist)
        val cosBeta = ((d * d + l1 * l1 - l2 * l2) / (2.0 * d * l1)).coerceIn(-1.0, 1.0)
        val beta = acos(cosBeta)
        val shoulderLiftRad = -(alpha + beta)

        // Maintain downward-pointing gripper tool frame
        val wrist1Rad = -(PI / 2.0) - (shoulderLiftRad + elbowRad)
        val wrist2Rad = -(PI / 2.0)
        val wrist3Rad = j1

        return Ur5eJointAngles(
            shoulderPanRad = j1,
            shoulderLiftRad = shoulderLiftRad,
            elbowRad = elbowRad,
            wrist1Rad = wrist1Rad,
            wrist2Rad = wrist2Rad,
            wrist3Rad = wrist3Rad
        )
    }
}

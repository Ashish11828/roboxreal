package com.example.robotics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class ChainVerificationResult(
    val chainValid: Boolean,
    val totalLinks: Int,
    val brokenIndex: Int? = null
)

/**
 * Cryptographic SHA-256 Audit Chain matching Deck Slide 5 & Slide 12 (100% chain_valid).
 * Chained across Firestore + local JSON mirror for tamper-evident industrial traceability.
 */
object Sha256AuditChain {
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    fun computeRecordHash(
        userId: String,
        operatorBadgeId: String,
        taskName: String,
        pickCoordsMm: String,
        placeCoordsMm: String,
        targetSpeedMmS: Double,
        urScriptHash: String,
        ros2GoalHash: String,
        previousHash: String
    ): String {
        val canonical = listOf(
            userId,
            operatorBadgeId,
            taskName,
            pickCoordsMm,
            placeCoordsMm,
            "%.1f".format(targetSpeedMmS),
            urScriptHash,
            ros2GoalHash,
            previousHash
        ).joinToString("|")
        return sha256Hex(canonical)
    }

    /**
     * Verifies cryptographic link continuity from oldest (genesis) to newest.
     * Input [chronologicalRecords] must be ordered oldest-first.
     */
    fun verifyChain(chronologicalRecords: List<AuditLogRecord>): ChainVerificationResult {
        if (chronologicalRecords.isEmpty()) {
            return ChainVerificationResult(chainValid = true, totalLinks = 0)
        }

        var expectedPrev = GENESIS_HASH
        chronologicalRecords.forEachIndexed { index, record ->
            if (record.previousHash != expectedPrev) {
                return ChainVerificationResult(
                    chainValid = false,
                    totalLinks = chronologicalRecords.size,
                    brokenIndex = index
                )
            }
            val recomputed = computeRecordHash(
                userId = record.userId,
                operatorBadgeId = record.operatorBadgeId,
                taskName = record.taskName,
                pickCoordsMm = record.pickCoordsMm,
                placeCoordsMm = record.placeCoordsMm,
                targetSpeedMmS = record.targetSpeedMmS,
                urScriptHash = record.urScriptHash,
                ros2GoalHash = record.ros2GoalHash,
                previousHash = record.previousHash
            )
            if (recomputed != record.recordHash) {
                return ChainVerificationResult(
                    chainValid = false,
                    totalLinks = chronologicalRecords.size,
                    brokenIndex = index
                )
            }
            expectedPrev = record.recordHash
        }

        return ChainVerificationResult(
            chainValid = true,
            totalLinks = chronologicalRecords.size
        )
    }

    fun persistLocalMirrorJson(file: File, chronologicalRecords: List<AuditLogRecord>) {
        runCatching {
            val array = JSONArray()
            chronologicalRecords.forEach { rec ->
                array.put(
                    JSONObject().apply {
                        put("id", rec.id)
                        put("operatorBadgeId", rec.operatorBadgeId)
                        put("taskName", rec.taskName)
                        put("plannerSource", rec.plannerSource)
                        put("pickCoordsMm", rec.pickCoordsMm)
                        put("placeCoordsMm", rec.placeCoordsMm)
                        put("targetSpeedMmS", rec.targetSpeedMmS)
                        put("guardrailLatencyUs", rec.guardrailLatencyUs)
                        put("recoveryDurationMs", rec.recoveryDurationMs)
                        put("urScriptHash", rec.urScriptHash)
                        put("ros2GoalHash", rec.ros2GoalHash)
                        put("previousHash", rec.previousHash)
                        put("recordHash", rec.recordHash)
                    }
                )
            }
            val root = JSONObject().apply {
                put("chain_valid", verifyChain(chronologicalRecords).chainValid)
                put("total_records", chronologicalRecords.size)
                put("records", array)
            }
            file.writeText(root.toString(2), Charsets.UTF_8)
        }
    }
}

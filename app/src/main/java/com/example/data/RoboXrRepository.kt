package com.example.data

import android.content.Context
import com.example.R
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.example.robotics.AuditLogRecord
import com.example.robotics.CompiledProgram
import com.example.robotics.GuardrailReport
import com.example.robotics.SavedTaskSpec
import com.example.robotics.Sha256AuditChain
import com.example.robotics.StructuredTaskSpec
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Authenticated Firestore Repository targeting the provisioned named enterprise database
 * (`R.string.firestore_database_id`) + local tamper-evident JSON audit mirror.
 */
class RoboXrRepository(
    private val db: FirebaseFirestore,
    private val localAuditFile: File,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        localAuditFile = File(context.applicationContext.filesDir, "roboxr_audit_chain.json")
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Operator must be signed in with Google before accessing Firestore.")
    }

    fun observeAuditLogs(userId: String): Flow<List<AuditLogRecord>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Session UID mismatch while observing auditLogs." }
        val path = "users/$uid/auditLogs"
        emitAll(
            db.collection("users").document(uid).collection("auditLogs")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    val list = snapshot.toObjects(
                        AuditLogRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    Sha256AuditChain.persistLocalMirrorJson(localAuditFile, list)
                    list
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeTaskSpecs(userId: String): Flow<List<SavedTaskSpec>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Session UID mismatch while observing taskSpecs." }
        val path = "users/$uid/taskSpecs"
        emitAll(
            db.collection("users").document(uid).collection("taskSpecs")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SavedTaskSpec::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun appendAuditLog(
        operatorBadgeId: String,
        spec: StructuredTaskSpec,
        guardrailReport: GuardrailReport,
        compiledProgram: CompiledProgram,
        previousHash: String,
        recoveryDurationMs: Long
    ): Result<AuditLogRecord> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/auditLogs"
        return try {
            val uid = requireUserId()
            require(guardrailReport.passed) { "Cannot deploy or audit while ISO 10218 Protective Stop is active." }

            val cleanBadge = operatorBadgeId.trim().take(40)
            require(cleanBadge.length >= 2) { "Operator Badge ID must be at least 2 characters." }

            val email = auth.currentUser?.email?.take(120)?.takeIf { it.length >= 3 }
                ?: "operator@roboxr.local"
            val taskName = spec.taskName.trim().take(120).ifBlank { "CNC Recovery Task" }
            val voiceIntent = spec.voiceIntent.trim().take(500).ifBlank { "Pick and place CNC billet" }
            val pickStr = spec.pickAnchorMm.formatCompact()
            val placeStr = spec.placeAnchorMm.formatCompact()
            val speed = spec.targetSpeedMmS.coerceIn(1.0, 250.0)
            val latencyUs = guardrailReport.latencyUs.coerceIn(0L, 10_000_000L)
            val durationMs = recoveryDurationMs.coerceIn(0L, 3_600_000L)
            val urPreview = compiledProgram.urScript.take(3900)

            val recordHash = Sha256AuditChain.computeRecordHash(
                userId = uid,
                operatorBadgeId = cleanBadge,
                taskName = taskName,
                pickCoordsMm = pickStr,
                placeCoordsMm = placeStr,
                targetSpeedMmS = speed,
                urScriptHash = compiledProgram.urScriptSha256,
                ros2GoalHash = compiledProgram.ros2GoalSha256,
                previousHash = previousHash
            )

            val docRef = db.collection("users").document(uid).collection("auditLogs").document()
            docPath = docRef.path

            val payload: Map<String, Any> = mapOf(
                "userId" to uid,
                "operatorEmail" to email,
                "operatorBadgeId" to cleanBadge,
                "taskName" to taskName,
                "voiceIntent" to voiceIntent,
                "plannerSource" to spec.plannerSource.wireValue,
                "pickCoordsMm" to pickStr,
                "placeCoordsMm" to placeStr,
                "targetSpeedMmS" to speed,
                "guardrailLatencyUs" to latencyUs,
                "recoveryDurationMs" to durationMs,
                "guardrailsPassed" to true,
                "urScriptHash" to compiledProgram.urScriptSha256,
                "ros2GoalHash" to compiledProgram.ros2GoalSha256,
                "previousHash" to previousHash,
                "recordHash" to recordHash,
                "urScriptPreview" to urPreview,
                "createdAt" to FieldValue.serverTimestamp()
            )

            docRef.set(payload).await()

            val createdRecord = AuditLogRecord(
                id = docRef.id,
                userId = uid,
                operatorEmail = email,
                operatorBadgeId = cleanBadge,
                taskName = taskName,
                voiceIntent = voiceIntent,
                plannerSource = spec.plannerSource.wireValue,
                pickCoordsMm = pickStr,
                placeCoordsMm = placeStr,
                targetSpeedMmS = speed,
                guardrailLatencyUs = latencyUs,
                recoveryDurationMs = durationMs,
                guardrailsPassed = true,
                urScriptHash = compiledProgram.urScriptSha256,
                ros2GoalHash = compiledProgram.ros2GoalSha256,
                previousHash = previousHash,
                recordHash = recordHash,
                urScriptPreview = urPreview,
                createdAt = Timestamp.now()
            )
            Result.success(createdRecord)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveTaskSpec(
        taskName: String,
        voiceIntent: String,
        pickX: Double,
        pickY: Double,
        pickZ: Double,
        placeX: Double,
        placeY: Double,
        placeZ: Double,
        targetSpeedMmS: Double
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/taskSpecs"
        return try {
            val uid = requireUserId()
            val cleanTitle = taskName.trim().take(120)
            val cleanIntent = voiceIntent.trim().take(500)
            require(cleanTitle.length >= 2) { "Task name must be at least 2 characters." }
            require(cleanIntent.length >= 2) { "Voice intent must be at least 2 characters." }

            val docRef = db.collection("users").document(uid).collection("taskSpecs").document()
            docPath = docRef.path

            val payload: Map<String, Any> = mapOf(
                "userId" to uid,
                "taskName" to cleanTitle,
                "voiceIntent" to cleanIntent,
                "pickX" to pickX.coerceIn(-900.0, 900.0),
                "pickY" to pickY.coerceIn(-900.0, 900.0),
                "pickZ" to pickZ.coerceIn(0.0, 900.0),
                "placeX" to placeX.coerceIn(-900.0, 900.0),
                "placeY" to placeY.coerceIn(-900.0, 900.0),
                "placeZ" to placeZ.coerceIn(0.0, 900.0),
                "targetSpeedMmS" to targetSpeedMmS.coerceIn(1.0, 250.0),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteTaskSpec(taskId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/taskSpecs/$taskId"
        return try {
            val uid = requireUserId()
            require(taskId.isNotBlank()) { "Task preset ID must not be blank." }
            val docRef = db.collection("users").document(uid).collection("taskSpecs").document(taskId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun getLocalMirrorPath(): String = localAuditFile.name
}

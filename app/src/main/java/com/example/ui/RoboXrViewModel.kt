package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiTrajectoryPlanner
import com.example.ai.OfflineTrajectoryPlanner
import com.example.data.RoboXrRepository
import com.example.robotics.AuditLogRecord
import com.example.robotics.CellStatus
import com.example.robotics.ChainVerificationResult
import com.example.robotics.CompiledProgram
import com.example.robotics.ElectrochromicLevel
import com.example.robotics.FaultPreset
import com.example.robotics.GuardrailReport
import com.example.robotics.ManufacturingCell
import com.example.robotics.ManufacturingHubCatalog
import com.example.robotics.RoboticsCodeCompiler
import com.example.robotics.SafetyGuardrails
import com.example.robotics.SavedTaskSpec
import com.example.robotics.Sha256AuditChain
import com.example.robotics.StructuredTaskSpec
import com.example.robotics.Vec3Mm
import com.example.robotics.VisorInteractionMode
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val VM_TAG = "RoboXrViewModel"

sealed interface FirestoreUiState<out T> {
    data object Loading : FirestoreUiState<Nothing>
    data class Success<T>(val data: T) : FirestoreUiState<T>
    data class Error(val message: String) : FirestoreUiState<Nothing>
}

sealed interface PlanningUiState {
    data object Idle : PlanningUiState
    data object Planning : PlanningUiState
    data class Ready(val spec: StructuredTaskSpec) : PlanningUiState
    data class Error(val message: String) : PlanningUiState
}

enum class PinchTargetMode {
    PICK,
    PLACE
}

enum class PipelineStage(val stepNumber: Int) {
    TEACH(1),
    INTERPRET(2),
    VALIDATE(3),
    DEPLOY(4)
}

data class RoboXrUiState(
    val activeStage: PipelineStage = PipelineStage.TEACH,
    val hubCells: List<ManufacturingCell> = ManufacturingHubCatalog.defaultCells,
    val selectedCellId: String = ManufacturingHubCatalog.defaultCells.first().cellId,
    val electrochromicLevel: ElectrochromicLevel = ElectrochromicLevel.SHOPFLOOR_TINT,
    val visorInteractionMode: VisorInteractionMode = VisorInteractionMode.PINCH_DRAG,
    val opticalSeeThroughMode: Boolean = false,
    val pinchTargetMode: PinchTargetMode = PinchTargetMode.PICK,
    val offlineEnginePreferred: Boolean = false,
    val voiceIntent: String = ManufacturingHubCatalog.defaultCells.first().defaultIntent,
    val pickAnchorMm: Vec3Mm = ManufacturingHubCatalog.defaultCells.first().defaultPickMm,
    val placeAnchorMm: Vec3Mm = ManufacturingHubCatalog.defaultCells.first().defaultPlaceMm,
    val requestedSpeedMmS: Double = ManufacturingHubCatalog.defaultCells.first().defaultSpeedMmS,
    val planningState: PlanningUiState = PlanningUiState.Idle,
    val activeSpec: StructuredTaskSpec? = null,
    val guardrailReport: GuardrailReport? = null,
    val compiledProgram: CompiledProgram? = null,
    val hitlDialogVisible: Boolean = false,
    val faultSheetVisible: Boolean = false,
    val operatorBadgeId: String = "OP-4021",
    val physicalClearanceConfirmed: Boolean = false,
    val deploying: Boolean = false,
    val lastDeployedRecord: AuditLogRecord? = null,
    val recoveryStopwatchRunning: Boolean = false,
    val recoveryElapsedMs: Long = 0L,
    val twinPlaying: Boolean = true,
    val manualStageIndex: Int? = null,
    val cloudSyncActive: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null
) {
    val selectedCell: ManufacturingCell
        get() = hubCells.firstOrNull { it.cellId == selectedCellId } ?: hubCells.first()
}

/**
 * Interactive XREAL Manufacturing Hub ViewModel.
 * - Starts immediately in interactive XR mode (zero login wall!).
 * - Never executes Firestore queries/writes while unauthenticated; writes to local tamper-evident
 *   SHA-256 JSON audit chain until the operator optionally signs in with Google for Cloud Sync.
 */
class RoboXrViewModel(
    private val localAuditFile: File,
    private val geminiPlanner: GeminiTrajectoryPlanner = GeminiTrajectoryPlanner()
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoboXrUiState())
    val uiState: StateFlow<RoboXrUiState> = _uiState.asStateFlow()

    private val _auditLogsState = MutableStateFlow<FirestoreUiState<List<AuditLogRecord>>>(
        FirestoreUiState.Success(emptyList())
    )
    val auditLogsState: StateFlow<FirestoreUiState<List<AuditLogRecord>>> = _auditLogsState.asStateFlow()

    private val _chainVerification = MutableStateFlow(ChainVerificationResult(chainValid = true, totalLinks = 0))
    val chainVerification: StateFlow<ChainVerificationResult> = _chainVerification.asStateFlow()

    private val _savedPresetsState = MutableStateFlow<FirestoreUiState<List<SavedTaskSpec>>>(
        FirestoreUiState.Success(emptyList())
    )
    val savedPresetsState: StateFlow<FirestoreUiState<List<SavedTaskSpec>>> = _savedPresetsState.asStateFlow()

    private var activeRepository: RoboXrRepository? = null
    private var activeUserId: String? = null
    private var auditObserverJob: Job? = null
    private var presetsObserverJob: Job? = null
    private var stopwatchJob: Job? = null
    private var stopwatchStartMs: Long = 0L

    private val localAuditCache = mutableListOf<AuditLogRecord>()
    private val localPresetsCache = mutableListOf<SavedTaskSpec>()

    init {
        runOfflineEngineInternal(startStopwatch = false)
    }

    /**
     * Dynamically binds authenticated Firestore cloud sync ONLY when Google Sign-In succeeds.
     * Passing null cleanly cancels all Firestore listeners and reverts to local on-device JSON mode.
     */
    fun bindAuthenticatedCloudSession(userId: String?, repository: RoboXrRepository?) {
        if (activeUserId == userId && (userId == null || activeRepository != null)) return

        auditObserverJob?.cancel()
        presetsObserverJob?.cancel()
        auditObserverJob = null
        presetsObserverJob = null
        activeUserId = userId
        activeRepository = repository

        if (userId.isNullOrBlank() || repository == null) {
            _uiState.update { it.copy(cloudSyncActive = false) }
            publishLocalAuditLogs()
            _savedPresetsState.value = FirestoreUiState.Success(localPresetsCache.toList())
            return
        }

        _uiState.update {
            it.copy(
                cloudSyncActive = true,
                statusBanner = "Cloud Audit Sync connected — Firestore SHA-256 chain active"
            )
        }
        _auditLogsState.value = FirestoreUiState.Loading
        _savedPresetsState.value = FirestoreUiState.Loading

        auditObserverJob = viewModelScope.launch {
            repository.observeAuditLogs(userId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(VM_TAG, "Error observing auditLogs", rootError)
                    _auditLogsState.value = FirestoreUiState.Error(rootError.message ?: "Failed to sync cloud audit chain")
                }
                .collect { logs ->
                    _auditLogsState.value = FirestoreUiState.Success(logs)
                    _chainVerification.value = Sha256AuditChain.verifyChain(logs)
                }
        }

        presetsObserverJob = viewModelScope.launch {
            repository.observeTaskSpecs(userId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(VM_TAG, "Error observing taskSpecs", rootError)
                    _savedPresetsState.value = FirestoreUiState.Error(rootError.message ?: "Failed to sync presets")
                }
                .collect { specs ->
                    _savedPresetsState.value = FirestoreUiState.Success(specs)
                }
        }
    }

    fun selectManufacturingCell(cellId: String) {
        val cell = _uiState.value.hubCells.firstOrNull { it.cellId == cellId } ?: return
        _uiState.update {
            it.copy(
                selectedCellId = cell.cellId,
                voiceIntent = cell.defaultIntent,
                pickAnchorMm = cell.defaultPickMm,
                placeAnchorMm = cell.defaultPlaceMm,
                requestedSpeedMmS = cell.defaultSpeedMmS,
                manualStageIndex = null,
                twinPlaying = true,
                errorBanner = null,
                statusBanner = "XREAL HUD locked to ${cell.cellId} · ${cell.machineName} (${cell.status.badgeText})"
            )
        }
        revalidateCurrentAnchorsFast()
    }

    fun cycleElectrochromicLevel() {
        val levels = ElectrochromicLevel.entries
        val next = levels[(_uiState.value.electrochromicLevel.ordinal + 1) % levels.size]
        _uiState.update {
            it.copy(
                electrochromicLevel = next,
                opticalSeeThroughMode = next == ElectrochromicLevel.OPTICAL_CLEAR,
                statusBanner = "XREAL Electrochromic Lens: ${next.label}"
            )
        }
    }

    fun setVisorInteractionMode(mode: VisorInteractionMode) {
        _uiState.update { it.copy(visorInteractionMode = mode) }
    }

    fun setPinchTargetMode(mode: PinchTargetMode) {
        _uiState.update { it.copy(pinchTargetMode = mode) }
    }

    fun setOfflineEnginePreferred(offline: Boolean) {
        _uiState.update { it.copy(offlineEnginePreferred = offline) }
    }

    fun toggleTwinPlayback() {
        _uiState.update {
            val nextPlay = !it.twinPlaying
            it.copy(
                twinPlaying = nextPlay,
                manualStageIndex = if (nextPlay) null else (it.manualStageIndex ?: 2)
            )
        }
    }

    fun scrubToStage(stageIndex: Int) {
        _uiState.update {
            it.copy(
                twinPlaying = false,
                manualStageIndex = stageIndex.coerceIn(0, 5)
            )
        }
    }

    fun updateVoiceIntent(intent: String) {
        _uiState.update { it.copy(voiceIntent = intent.take(500), errorBanner = null) }
    }

    fun updatePickAnchor(anchor: Vec3Mm) {
        ensureStopwatchStarted()
        val clamped = Vec3Mm(
            x = anchor.x.coerceIn(60.0, 780.0),
            y = anchor.y.coerceIn(-480.0, 480.0),
            z = anchor.z.coerceIn(10.0, 440.0)
        )
        _uiState.update {
            it.copy(
                pickAnchorMm = clamped,
                activeStage = PipelineStage.TEACH,
                errorBanner = null
            )
        }
        revalidateCurrentAnchorsFast()
    }

    fun updatePlaceAnchor(anchor: Vec3Mm) {
        ensureStopwatchStarted()
        val clamped = Vec3Mm(
            x = anchor.x.coerceIn(60.0, 780.0),
            y = anchor.y.coerceIn(-480.0, 480.0),
            z = anchor.z.coerceIn(10.0, 440.0)
        )
        _uiState.update {
            it.copy(
                placeAnchorMm = clamped,
                activeStage = PipelineStage.TEACH,
                errorBanner = null
            )
        }
        revalidateCurrentAnchorsFast()
    }

    fun updateActiveAnchorZ(newZ: Double) {
        val state = _uiState.value
        if (state.pinchTargetMode == PinchTargetMode.PICK) {
            updatePickAnchor(state.pickAnchorMm.copy(z = newZ))
        } else {
            updatePlaceAnchor(state.placeAnchorMm.copy(z = newZ))
        }
    }

    fun pinchSetFromSpatialCursor(cursorMm: Vec3Mm) {
        if (_uiState.value.pinchTargetMode == PinchTargetMode.PICK) {
            updatePickAnchor(cursorMm)
        } else {
            updatePlaceAnchor(cursorMm)
        }
    }

    fun updateRequestedSpeed(speedMmS: Double) {
        _uiState.update {
            it.copy(
                requestedSpeedMmS = speedMmS.coerceIn(20.0, 450.0),
                errorBanner = null
            )
        }
        revalidateCurrentAnchorsFast()
    }

    fun runGeminiPlan() {
        ensureStopwatchStarted()
        val state = _uiState.value
        val intentText = state.voiceIntent.trim().ifBlank {
            "Pick jammed workpiece from CNC chuck and transfer to reject tray"
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeStage = PipelineStage.INTERPRET,
                    planningState = PlanningUiState.Planning,
                    errorBanner = null,
                    statusBanner = "Stage 2: Gemini 3.8 Flash generating constrained 6-stage trajectory JSON..."
                )
            }

            val result = geminiPlanner.generateTaskSpec(
                voiceIntent = intentText,
                pickAnchorMm = state.pickAnchorMm,
                placeAnchorMm = state.placeAnchorMm,
                requestedSpeedMmS = state.requestedSpeedMmS
            )

            result.fold(
                onSuccess = { spec -> applyPlannedSpec(spec) },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "Firebase AI Logic planning failed."
                    Log.e(VM_TAG, "Gemini planning error: $msg", err)
                    _uiState.update {
                        it.copy(
                            planningState = PlanningUiState.Error(msg),
                            errorBanner = "Gemini AI Logic Error: $msg — Retry or tap 'Offline (<5ms)' for on-device planning.",
                            statusBanner = null
                        )
                    }
                }
            )
        }
    }

    fun runOfflineEngine() {
        runOfflineEngineInternal(startStopwatch = true)
    }

    private fun runOfflineEngineInternal(startStopwatch: Boolean) {
        if (startStopwatch) ensureStopwatchStarted()
        val state = _uiState.value
        val spec = OfflineTrajectoryPlanner.generateTaskSpec(
            voiceIntent = state.voiceIntent.ifBlank { "Clear jammed billet from chuck jaw" },
            pickAnchorMm = state.pickAnchorMm,
            placeAnchorMm = state.placeAnchorMm,
            requestedSpeedMmS = state.requestedSpeedMmS
        )
        applyPlannedSpec(spec)
    }

    fun triggerOneShot30sRecovery() {
        resetAndStartStopwatch()
        val cell = _uiState.value.selectedCell
        _uiState.update {
            it.copy(
                pickAnchorMm = cell.defaultPickMm,
                placeAnchorMm = cell.defaultPlaceMm,
                requestedSpeedMmS = 180.0,
                voiceIntent = "1-Shot 30s Recovery (${cell.cellId} ${cell.machineName}): extract jammed billet over fixture box to reject tray",
                physicalClearanceConfirmed = false,
                lastDeployedRecord = null,
                twinPlaying = true,
                manualStageIndex = null,
                errorBanner = null,
                statusBanner = "30s Anomaly Recovery started on ${cell.cellId} — planning trajectory..."
            )
        }
        if (_uiState.value.offlineEnginePreferred) {
            runOfflineEngineInternal(startStopwatch = false)
        } else {
            runGeminiPlan()
        }
    }

    fun setFaultSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(faultSheetVisible = visible) }
    }

    fun injectFault(preset: FaultPreset) {
        ensureStopwatchStarted()
        _uiState.update {
            it.copy(
                faultSheetVisible = false,
                pickAnchorMm = preset.pickMm,
                placeAnchorMm = preset.placeMm,
                requestedSpeedMmS = preset.speedMmS,
                voiceIntent = "FAULT TEST (${preset.label}): ${preset.description}",
                physicalClearanceConfirmed = false,
                errorBanner = null
            )
        }
        val spec = OfflineTrajectoryPlanner.generateTaskSpec(
            voiceIntent = _uiState.value.voiceIntent,
            pickAnchorMm = preset.pickMm,
            placeAnchorMm = preset.placeMm,
            requestedSpeedMmS = preset.speedMmS
        )
        applyPlannedSpec(spec)
    }

    fun openHitlGateDialog() {
        val report = _uiState.value.guardrailReport
        if (report == null || !report.passed) {
            _uiState.update {
                it.copy(
                    errorBanner = "PROTECTIVE STOP ACTIVE: Resolve all ISO 10218 violations before Stage 4 Sign-Off."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                hitlDialogVisible = true,
                physicalClearanceConfirmed = false,
                errorBanner = null
            )
        }
    }

    fun dismissHitlGateDialog() {
        _uiState.update { it.copy(hitlDialogVisible = false) }
    }

    fun updateOperatorBadgeId(badge: String) {
        _uiState.update { it.copy(operatorBadgeId = badge.take(40)) }
    }

    fun setPhysicalClearanceConfirmed(confirmed: Boolean) {
        _uiState.update { it.copy(physicalClearanceConfirmed = confirmed) }
    }

    fun approveAndDeployHitl() {
        val state = _uiState.value
        val spec = state.activeSpec ?: return
        val report = state.guardrailReport ?: return
        val program = state.compiledProgram ?: return

        if (!report.passed || !state.physicalClearanceConfirmed) {
            _uiState.update {
                it.copy(errorBanner = "Physical workcell clearance and ISO 10218 pass are mandatory.")
            }
            return
        }

        val badge = state.operatorBadgeId.trim()
        if (badge.length < 2) {
            _uiState.update {
                it.copy(errorBanner = "Enter a valid Qualified Operator Badge ID (min 2 chars).")
            }
            return
        }

        val currentAuditLogs = (auditLogsState.value as? FirestoreUiState.Success)?.data.orEmpty()
        val previousHash = currentAuditLogs.lastOrNull()?.recordHash ?: Sha256AuditChain.GENESIS_HASH
        val elapsedMs = stopStopwatchAndCapture()

        val repo = activeRepository
        val uid = activeUserId

        if (repo != null && !uid.isNullOrBlank()) {
            // Cloud-authenticated path: write to Firestore + local JSON mirror
            viewModelScope.launch {
                _uiState.update { it.copy(deploying = true, errorBanner = null) }
                val result = repo.appendAuditLog(
                    operatorBadgeId = badge,
                    spec = spec,
                    guardrailReport = report,
                    compiledProgram = program,
                    previousHash = previousHash,
                    recoveryDurationMs = elapsedMs
                )
                result.fold(
                    onSuccess = { record ->
                        markSelectedCellRecovered(record, elapsedMs, cloudBacked = true)
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                deploying = false,
                                errorBanner = "Cloud Audit write failed: ${err.localizedMessage ?: "Firestore error"}"
                            )
                        }
                    }
                )
            }
        } else {
            // Local shopfloor edge mode (unauthenticated — zero Firestore calls)
            val pickStr = spec.pickAnchorMm.formatCompact()
            val placeStr = spec.placeAnchorMm.formatCompact()
            val speed = spec.targetSpeedMmS.coerceIn(1.0, 250.0)
            val recordHash = Sha256AuditChain.computeRecordHash(
                userId = "local_edge_operator",
                operatorBadgeId = badge,
                taskName = spec.taskName,
                pickCoordsMm = pickStr,
                placeCoordsMm = placeStr,
                targetSpeedMmS = speed,
                urScriptHash = program.urScriptSha256,
                ros2GoalHash = program.ros2GoalSha256,
                previousHash = previousHash
            )
            val localRecord = AuditLogRecord(
                id = "local_${UUID.randomUUID().toString().take(8)}",
                userId = "local_edge_operator",
                operatorEmail = "edge-local@xreal.cell",
                operatorBadgeId = badge,
                taskName = "${state.selectedCell.cellId} · ${spec.taskName}",
                voiceIntent = spec.voiceIntent,
                plannerSource = spec.plannerSource.wireValue,
                pickCoordsMm = pickStr,
                placeCoordsMm = placeStr,
                targetSpeedMmS = speed,
                guardrailLatencyUs = report.latencyUs,
                recoveryDurationMs = elapsedMs,
                guardrailsPassed = true,
                urScriptHash = program.urScriptSha256,
                ros2GoalHash = program.ros2GoalSha256,
                previousHash = previousHash,
                recordHash = recordHash,
                urScriptPreview = program.urScript.take(3900),
                createdAt = Timestamp.now()
            )
            localAuditCache.add(localRecord)
            publishLocalAuditLogs()
            markSelectedCellRecovered(localRecord, elapsedMs, cloudBacked = false)
        }
    }

    private fun markSelectedCellRecovered(
        record: AuditLogRecord,
        elapsedMs: Long,
        cloudBacked: Boolean
    ) {
        val currentId = _uiState.value.selectedCellId
        _uiState.update { state ->
            val updatedCells = state.hubCells.map { cell ->
                if (cell.cellId == currentId) {
                    cell.copy(status = CellStatus.RECOVERED, doorOpen = false)
                } else {
                    cell
                }
            }
            state.copy(
                hubCells = updatedCells,
                deploying = false,
                hitlDialogVisible = false,
                activeStage = PipelineStage.DEPLOY,
                lastDeployedRecord = record,
                twinPlaying = true,
                manualStageIndex = null,
                statusBanner = "UR5e DISPATCHED (${if (cloudBacked) "Firestore + JSON" else "Local JSON"}) · ${record.recordHash.take(10)}… in ${"%.1f".format(elapsedMs / 1000.0)}s"
            )
        }
    }

    fun saveCurrentAsPreset() {
        val state = _uiState.value
        val title = "${state.selectedCell.cellId} · ${state.activeSpec?.taskName ?: "CNC Anchor"}"
        val repo = activeRepository
        val uid = activeUserId
        if (repo != null && !uid.isNullOrBlank()) {
            viewModelScope.launch {
                val result = repo.saveTaskSpec(
                    taskName = title,
                    voiceIntent = state.voiceIntent,
                    pickX = state.pickAnchorMm.x,
                    pickY = state.pickAnchorMm.y,
                    pickZ = state.pickAnchorMm.z,
                    placeX = state.placeAnchorMm.x,
                    placeY = state.placeAnchorMm.y,
                    placeZ = state.placeAnchorMm.z,
                    targetSpeedMmS = state.requestedSpeedMmS.coerceAtMost(250.0)
                )
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(statusBanner = "Saved preset '$title' to Firestore.") }
                    },
                    onFailure = { err ->
                        _uiState.update { it.copy(errorBanner = "Preset save failed: ${err.localizedMessage}") }
                    }
                )
            }
        } else {
            val localPreset = SavedTaskSpec(
                id = "preset_${UUID.randomUUID().toString().take(8)}",
                userId = "local_edge_operator",
                taskName = title,
                voiceIntent = state.voiceIntent,
                pickX = state.pickAnchorMm.x,
                pickY = state.pickAnchorMm.y,
                pickZ = state.pickAnchorMm.z,
                placeX = state.placeAnchorMm.x,
                placeY = state.placeAnchorMm.y,
                placeZ = state.placeAnchorMm.z,
                targetSpeedMmS = state.requestedSpeedMmS.coerceAtMost(250.0),
                createdAt = Timestamp.now(),
                updatedAt = Timestamp.now()
            )
            localPresetsCache.add(0, localPreset)
            _savedPresetsState.value = FirestoreUiState.Success(localPresetsCache.toList())
            _uiState.update { it.copy(statusBanner = "Saved local preset '$title' (Sign in for Cloud Sync).") }
        }
    }

    fun loadPreset(preset: SavedTaskSpec) {
        _uiState.update {
            it.copy(
                voiceIntent = preset.voiceIntent,
                pickAnchorMm = Vec3Mm(preset.pickX, preset.pickY, preset.pickZ),
                placeAnchorMm = Vec3Mm(preset.placeX, preset.placeY, preset.placeZ),
                requestedSpeedMmS = preset.targetSpeedMmS,
                statusBanner = "Loaded preset '${preset.taskName}'"
            )
        }
        runOfflineEngineInternal(startStopwatch = true)
    }

    fun deletePreset(taskId: String) {
        val repo = activeRepository
        if (repo != null && !activeUserId.isNullOrBlank()) {
            viewModelScope.launch {
                repo.deleteTaskSpec(taskId).onFailure { err ->
                    _uiState.update { it.copy(errorBanner = "Delete failed: ${err.localizedMessage}") }
                }
            }
        } else {
            localPresetsCache.removeAll { it.id == taskId }
            _savedPresetsState.value = FirestoreUiState.Success(localPresetsCache.toList())
        }
    }

    fun getLocalMirrorFilename(): String = localAuditFile.name

    private fun publishLocalAuditLogs() {
        val snapshot = localAuditCache.toList()
        Sha256AuditChain.persistLocalMirrorJson(localAuditFile, snapshot)
        _auditLogsState.value = FirestoreUiState.Success(snapshot)
        _chainVerification.value = Sha256AuditChain.verifyChain(snapshot)
    }

    private fun revalidateCurrentAnchorsFast() {
        val state = _uiState.value
        val spec = OfflineTrajectoryPlanner.generateTaskSpec(
            voiceIntent = state.voiceIntent.ifBlank { "CNC Pick & Place" },
            pickAnchorMm = state.pickAnchorMm,
            placeAnchorMm = state.placeAnchorMm,
            requestedSpeedMmS = state.requestedSpeedMmS
        )
        applyPlannedSpec(spec)
    }

    private fun applyPlannedSpec(spec: StructuredTaskSpec) {
        val report = SafetyGuardrails.validate(spec)
        val compiled = RoboticsCodeCompiler.compile(spec, report)

        _uiState.update {
            it.copy(
                activeStage = PipelineStage.VALIDATE,
                planningState = PlanningUiState.Ready(spec),
                activeSpec = spec,
                guardrailReport = report,
                compiledProgram = compiled,
                statusBanner = if (report.passed) {
                    "Stage 3 PASS (${report.latencyUs} µs · ${spec.plannerSource.displayLabel} ${spec.planLatencyMs} ms) — Ready for HITL Sign-Off"
                } else {
                    null
                },
                errorBanner = if (!report.passed) {
                    "PROTECTIVE STOP INTERLOCK (${report.latencyUs} µs): ${report.violations.firstOrNull()?.message ?: "Safety boundary breached"}"
                } else {
                    null
                }
            )
        }
    }

    private fun ensureStopwatchStarted() {
        if (!_uiState.value.recoveryStopwatchRunning) {
            resetAndStartStopwatch()
        }
    }

    private fun resetAndStartStopwatch() {
        stopwatchJob?.cancel()
        stopwatchStartMs = System.currentTimeMillis()
        _uiState.update { it.copy(recoveryStopwatchRunning = true, recoveryElapsedMs = 0L) }
        stopwatchJob = viewModelScope.launch {
            while (isActive) {
                delay(100L)
                _uiState.update {
                    it.copy(recoveryElapsedMs = System.currentTimeMillis() - stopwatchStartMs)
                }
            }
        }
    }

    private fun stopStopwatchAndCapture(): Long {
        stopwatchJob?.cancel()
        stopwatchJob = null
        val elapsed = if (stopwatchStartMs > 0L) {
            (System.currentTimeMillis() - stopwatchStartMs).coerceAtLeast(100L)
        } else {
            _uiState.value.recoveryElapsedMs
        }
        _uiState.update { it.copy(recoveryStopwatchRunning = false, recoveryElapsedMs = elapsed) }
        return elapsed
    }
}

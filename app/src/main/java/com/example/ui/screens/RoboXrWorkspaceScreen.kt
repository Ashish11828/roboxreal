package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.robotics.CellStatus
import com.example.robotics.ManufacturingCell
import com.example.ui.PipelineStage
import com.example.ui.RoboXrUiState
import com.example.ui.RoboXrViewModel
import com.example.ui.components.Ur5eDigitalTwinCanvas
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.HitlAmber
import com.example.ui.theme.InterlockCrimson
import com.example.ui.theme.MonoTelemetryStyle
import com.example.ui.theme.RoboXrTheme
import com.example.ui.theme.SafetyEmerald
import com.example.xr.XrealDeviceState
import com.example.xr.XrealSdkBridge

/**
 * Interactive XREAL Smart Glasses Manufacturing Hub Workspace.
 * Launches directly into the spatial CNC workcell HUD with zero login barrier.
 * Optional non-blocking Google Sign-In chip enables Cloud Firestore audit sync.
 */
@Composable
fun RoboXrWorkspaceScreen(
    viewModel: RoboXrViewModel,
    xrealBridge: XrealSdkBridge,
    operatorEmail: String?,
    onCloudSyncClick: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val xrealState by xrealBridge.deviceState.collectAsStateWithLifecycle()
    val auditLogsState by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val chainVerification by viewModel.chainVerification.collectAsStateWithLifecycle()
    val savedPresetsState by viewModel.savedPresetsState.collectAsStateWithLifecycle()

    LaunchedEffect(
        state.selectedCellId,
        state.activeSpec?.taskName,
        state.guardrailReport?.passed,
        state.pickAnchorMm,
        state.placeAnchorMm
    ) {
        val isStop = state.guardrailReport?.protectiveStopActive == true
        val statusText = if (isStop) {
            "PROTECTIVE STOP ACTIVE — BLOCKED"
        } else {
            "${state.selectedCell.cellId} ISO 10218 PASS · STAGE ${state.activeStage.stepNumber}/4"
        }
        xrealBridge.updateExternalHudContent(
            taskName = "${state.selectedCell.machineName} · ${state.activeSpec?.taskName ?: "Tending"}",
            statusLine = statusText,
            pickLabel = state.pickAnchorMm.formatCompact(),
            placeLabel = state.placeAnchorMm.formatCompact(),
            isProtectiveStop = isStop
        )
    }

    LaunchedEffect(state.guardrailReport?.protectiveStopActive) {
        if (state.guardrailReport?.protectiveStopActive == true) {
            xrealBridge.pulseHaptic(isAlert = true)
        }
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            xrealBridge.startVoiceRecognition(
                onResult = { transcript -> viewModel.updateVoiceIntent(transcript) },
                onError = {}
            )
        }
    }

    val onVoiceMicToggle: () -> Unit = {
        if (xrealState.voiceListening) {
            xrealBridge.stopVoiceRecognition()
        } else {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                xrealBridge.startVoiceRecognition(
                    onResult = { transcript -> viewModel.updateVoiceIntent(transcript) },
                    onError = {}
                )
            } else {
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    var compactSelectedTab by remember { mutableIntStateOf(0) }
    var expandedRightTab by remember { mutableIntStateOf(0) }

    RoboXrTheme(opticalSeeThroughMode = state.opticalSeeThroughMode) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpandedXrLayout = maxWidth >= 840.dp

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    Column {
                        XrTelemetryTopBar(
                            state = state,
                            xrealState = xrealState,
                            operatorEmail = operatorEmail,
                            onCloudSyncClick = onCloudSyncClick
                        )
                        ManufacturingHubCellStrip(
                            cells = state.hubCells,
                            selectedCellId = state.selectedCellId,
                            onSelectCell = { id ->
                                xrealBridge.pulseHaptic(false)
                                viewModel.selectManufacturingCell(id)
                            }
                        )
                    }
                },
                bottomBar = {
                    if (isExpandedXrLayout) {
                        XrealBottomActionBar(
                            state = state,
                            onOneShotRecovery = {
                                xrealBridge.pulseHaptic(false)
                                viewModel.triggerOneShot30sRecovery()
                            },
                            onApproveDeploy = viewModel::openHitlGateDialog,
                            onFaultInject = { viewModel.setFaultSheetVisible(true) },
                            modifier = Modifier.navigationBarsPadding()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .navigationBarsPadding()
                        ) {
                            XrealBottomActionBar(
                                state = state,
                                onOneShotRecovery = {
                                    xrealBridge.pulseHaptic(false)
                                    viewModel.triggerOneShot30sRecovery()
                                },
                                onApproveDeploy = viewModel::openHitlGateDialog,
                                onFaultInject = { viewModel.setFaultSheetVisible(true) }
                            )
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                windowInsets = WindowInsets(0, 0, 0, 0)
                            ) {
                                NavigationBarItem(
                                    selected = compactSelectedTab == 0,
                                    onClick = { compactSelectedTab = 0 },
                                    icon = { Icon(Icons.Filled.ViewInAr, contentDescription = null) },
                                    label = { Text("XR Hub", maxLines = 1) },
                                    modifier = Modifier.testTag("nav_tab_twin")
                                )
                                NavigationBarItem(
                                    selected = compactSelectedTab == 1,
                                    onClick = { compactSelectedTab = 1 },
                                    icon = { Icon(Icons.Filled.TouchApp, contentDescription = null) },
                                    label = { Text(stringResource(R.string.tab_teach), maxLines = 1) },
                                    modifier = Modifier.testTag("nav_tab_teach")
                                )
                                NavigationBarItem(
                                    selected = compactSelectedTab == 2,
                                    onClick = { compactSelectedTab = 2 },
                                    icon = { Icon(Icons.Filled.Code, contentDescription = null) },
                                    label = { Text("URScript", maxLines = 1) },
                                    modifier = Modifier.testTag("nav_tab_code")
                                )
                                NavigationBarItem(
                                    selected = compactSelectedTab == 3,
                                    onClick = { compactSelectedTab = 3 },
                                    icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                                    label = { Text("Audit", maxLines = 1) },
                                    modifier = Modifier.testTag("nav_tab_audit")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PipelineStageRibbon(
                        activeStage = state.activeStage,
                        protectiveStop = state.guardrailReport?.protectiveStopActive == true
                    )

                    StatusOrErrorBanner(
                        errorBanner = state.errorBanner,
                        statusBanner = state.statusBanner
                    )

                    if (isExpandedXrLayout) {
                        // EXPANDED XREAL SMART GLASSES 3-COLUMN HUD
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1.05f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SafetyChecklistPanel(state = state)
                                TeachAndHudPanel(
                                    state = state,
                                    xrealState = xrealState,
                                    savedPresetsState = savedPresetsState,
                                    onToggleImu = xrealBridge::setImuTrackingEnabled,
                                    onRecenterImu = xrealBridge::recenterImu,
                                    onPinchFromXrealRay = {
                                        xrealBridge.pulseHaptic(false)
                                        viewModel.pinchSetFromSpatialCursor(xrealState.raycastCursorMm)
                                    },
                                    onSelectPinchTarget = viewModel::setPinchTargetMode,
                                    onUpdatePick = viewModel::updatePickAnchor,
                                    onUpdatePlace = viewModel::updatePlaceAnchor,
                                    onUpdateSpeed = viewModel::updateRequestedSpeed,
                                    onUpdateIntent = viewModel::updateVoiceIntent,
                                    onVoiceMicToggle = onVoiceMicToggle,
                                    onToggleOfflineEngine = viewModel::setOfflineEnginePreferred,
                                    onRunGeminiPlan = viewModel::runGeminiPlan,
                                    onRunOfflinePlan = viewModel::runOfflineEngine,
                                    onSavePreset = viewModel::saveCurrentAsPreset,
                                    onLoadPreset = viewModel::loadPreset,
                                    onDeletePreset = viewModel::deletePreset
                                )
                            }

                            Ur5eDigitalTwinCanvas(
                                cell = state.selectedCell,
                                spec = state.activeSpec,
                                guardrailReport = state.guardrailReport,
                                pickAnchorMm = state.pickAnchorMm,
                                placeAnchorMm = state.placeAnchorMm,
                                xrealState = xrealState,
                                pinchTargetMode = state.pinchTargetMode,
                                visorInteractionMode = state.visorInteractionMode,
                                electrochromicLevel = state.electrochromicLevel,
                                isPlaying = state.twinPlaying,
                                manualStageIndex = state.manualStageIndex,
                                onTogglePlay = viewModel::toggleTwinPlayback,
                                onSelectStageIndex = viewModel::scrubToStage,
                                onSetInteractionMode = viewModel::setVisorInteractionMode,
                                onSelectPinchTarget = viewModel::setPinchTargetMode,
                                onCycleElectrochromic = viewModel::cycleElectrochromicLevel,
                                onMoveActiveAnchor = { pt ->
                                    viewModel.pinchSetFromSpatialCursor(pt)
                                },
                                onStepActiveAnchorZ = { newZ ->
                                    xrealBridge.pulseHaptic(false)
                                    viewModel.updateActiveAnchorZ(newZ)
                                },
                                modifier = Modifier
                                    .weight(1.4f)
                                    .fillMaxHeight()
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1.0f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TabRow(
                                    selectedTabIndex = expandedRightTab,
                                    containerColor = MaterialTheme.colorScheme.surface
                                ) {
                                    Tab(
                                        selected = expandedRightTab == 0,
                                        onClick = { expandedRightTab = 0 },
                                        text = { Text("URScript / ROS 2") }
                                    )
                                    Tab(
                                        selected = expandedRightTab == 1,
                                        onClick = { expandedRightTab = 1 },
                                        text = { Text("SHA-256 Audit") }
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    if (expandedRightTab == 0) {
                                        RoboticsCodePanel(
                                            state = state,
                                            onOpenHitlGate = viewModel::openHitlGateDialog
                                        )
                                    } else {
                                        AuditChainPanel(
                                            auditLogsState = auditLogsState,
                                            chainVerification = chainVerification,
                                            localMirrorFileName = viewModel.getLocalMirrorFilename()
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // COMPACT HANDHELD: Launch straight into the giant interactive XREAL XR Visor!
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (compactSelectedTab) {
                                0 -> {
                                    Ur5eDigitalTwinCanvas(
                                        cell = state.selectedCell,
                                        spec = state.activeSpec,
                                        guardrailReport = state.guardrailReport,
                                        pickAnchorMm = state.pickAnchorMm,
                                        placeAnchorMm = state.placeAnchorMm,
                                        xrealState = xrealState,
                                        pinchTargetMode = state.pinchTargetMode,
                                        visorInteractionMode = state.visorInteractionMode,
                                        electrochromicLevel = state.electrochromicLevel,
                                        isPlaying = state.twinPlaying,
                                        manualStageIndex = state.manualStageIndex,
                                        onTogglePlay = viewModel::toggleTwinPlayback,
                                        onSelectStageIndex = viewModel::scrubToStage,
                                        onSetInteractionMode = viewModel::setVisorInteractionMode,
                                        onSelectPinchTarget = viewModel::setPinchTargetMode,
                                        onCycleElectrochromic = viewModel::cycleElectrochromicLevel,
                                        onMoveActiveAnchor = { pt ->
                                            viewModel.pinchSetFromSpatialCursor(pt)
                                        },
                                        onStepActiveAnchorZ = { newZ ->
                                            xrealBridge.pulseHaptic(false)
                                            viewModel.updateActiveAnchorZ(newZ)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(370.dp)
                                    )
                                    SafetyChecklistPanel(state = state)
                                }
                                1 -> {
                                    Ur5eDigitalTwinCanvas(
                                        cell = state.selectedCell,
                                        spec = state.activeSpec,
                                        guardrailReport = state.guardrailReport,
                                        pickAnchorMm = state.pickAnchorMm,
                                        placeAnchorMm = state.placeAnchorMm,
                                        xrealState = xrealState,
                                        pinchTargetMode = state.pinchTargetMode,
                                        visorInteractionMode = state.visorInteractionMode,
                                        electrochromicLevel = state.electrochromicLevel,
                                        isPlaying = state.twinPlaying,
                                        manualStageIndex = state.manualStageIndex,
                                        onTogglePlay = viewModel::toggleTwinPlayback,
                                        onSelectStageIndex = viewModel::scrubToStage,
                                        onSetInteractionMode = viewModel::setVisorInteractionMode,
                                        onSelectPinchTarget = viewModel::setPinchTargetMode,
                                        onCycleElectrochromic = viewModel::cycleElectrochromicLevel,
                                        onMoveActiveAnchor = { pt ->
                                            viewModel.pinchSetFromSpatialCursor(pt)
                                        },
                                        onStepActiveAnchorZ = { newZ ->
                                            xrealBridge.pulseHaptic(false)
                                            viewModel.updateActiveAnchorZ(newZ)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(230.dp)
                                    )
                                    TeachAndHudPanel(
                                        state = state,
                                        xrealState = xrealState,
                                        savedPresetsState = savedPresetsState,
                                        onToggleImu = xrealBridge::setImuTrackingEnabled,
                                        onRecenterImu = xrealBridge::recenterImu,
                                        onPinchFromXrealRay = {
                                            xrealBridge.pulseHaptic(false)
                                            viewModel.pinchSetFromSpatialCursor(xrealState.raycastCursorMm)
                                        },
                                        onSelectPinchTarget = viewModel::setPinchTargetMode,
                                        onUpdatePick = viewModel::updatePickAnchor,
                                        onUpdatePlace = viewModel::updatePlaceAnchor,
                                        onUpdateSpeed = viewModel::updateRequestedSpeed,
                                        onUpdateIntent = viewModel::updateVoiceIntent,
                                        onVoiceMicToggle = onVoiceMicToggle,
                                        onToggleOfflineEngine = viewModel::setOfflineEnginePreferred,
                                        onRunGeminiPlan = viewModel::runGeminiPlan,
                                        onRunOfflinePlan = viewModel::runOfflineEngine,
                                        onSavePreset = viewModel::saveCurrentAsPreset,
                                        onLoadPreset = viewModel::loadPreset,
                                        onDeletePreset = viewModel::deletePreset
                                    )
                                }
                                2 -> {
                                    SafetyChecklistPanel(state = state)
                                    RoboticsCodePanel(
                                        state = state,
                                        onOpenHitlGate = viewModel::openHitlGateDialog
                                    )
                                }
                                3 -> {
                                    AuditChainPanel(
                                        auditLogsState = auditLogsState,
                                        chainVerification = chainVerification,
                                        localMirrorFileName = viewModel.getLocalMirrorFilename()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.hitlDialogVisible) {
                HitlOperatorGateDialog(
                    state = state,
                    operatorEmail = operatorEmail ?: "edge-local@xreal.cell",
                    onBadgeChange = viewModel::updateOperatorBadgeId,
                    onConfirmClearanceChange = viewModel::setPhysicalClearanceConfirmed,
                    onDismiss = viewModel::dismissHitlGateDialog,
                    onApproveAndDeploy = {
                        xrealBridge.pulseHaptic(false)
                        viewModel.approveAndDeployHitl()
                    }
                )
            }

            if (state.faultSheetVisible) {
                FaultInjectionDialog(
                    onDismiss = { viewModel.setFaultSheetVisible(false) },
                    onSelectFault = { preset ->
                        xrealBridge.pulseHaptic(isAlert = true)
                        viewModel.injectFault(preset)
                    }
                )
            }
        }
    }
}

@Composable
private fun ManufacturingHubCellStrip(
    cells: List<ManufacturingCell>,
    selectedCellId: String,
    onSelectCell: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cells.forEach { cell ->
            val selected = cell.cellId == selectedCellId
            val statusColor = when (cell.status) {
                CellStatus.JAM_ALERT -> InterlockCrimson
                CellStatus.RUNNING -> ElectricCyan
                CellStatus.CHANGEOVER -> HitlAmber
                CellStatus.RECOVERED -> SafetyEmerald
            }
            Surface(
                onClick = { onSelectCell(cell.cellId) },
                color = if (selected) statusColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    width = if (selected) 1.4.dp else 1.dp,
                    color = if (selected) statusColor else MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("hub_cell_${cell.cellId.lowercase()}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column {
                        Text(
                            text = "${cell.cellId} · ${cell.machineName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            text = "${cell.cobotModel} · ${cell.status.badgeText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun XrTelemetryTopBar(
    state: RoboXrUiState,
    xrealState: XrealDeviceState,
    operatorEmail: String?,
    onCloudSyncClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ROBOXR AI",
                        style = MaterialTheme.typography.titleMedium,
                        color = ElectricCyan
                    )
                    Surface(
                        color = if (xrealState.xrealUsbConnected || xrealState.externalHudDisplayConnected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (xrealState.xrealUsbConnected) "XREAL USB-C" else "XREAL HUB",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (xrealState.xrealUsbConnected) SafetyEmerald else ElectricCyan,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Live 30s Anomaly Recovery Stopwatch KPI
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    1.dp,
                    if (state.recoveryElapsedMs > 30_000L) HitlAmber else SafetyEmerald.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = "Recovery Timer",
                        tint = if (state.recoveryElapsedMs > 30_000L) HitlAmber else SafetyEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "${"%.1f".format(state.recoveryElapsedMs / 1000.0)}s",
                        style = MonoTelemetryStyle,
                        color = if (state.recoveryElapsedMs > 30_000L) HitlAmber else SafetyEmerald
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Non-blocking Optional Cloud Audit Sync chip (Google Sign-In via Credential Manager)
            Surface(
                onClick = onCloudSyncClick,
                color = if (operatorEmail != null) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    1.dp,
                    if (operatorEmail != null) SafetyEmerald else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.testTag("cloud_sync_auth_chip")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (operatorEmail != null) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = if (operatorEmail != null) "Cloud Synced — tap to sign out" else "Sign in with Google for Cloud Audit Sync",
                        tint = if (operatorEmail != null) SafetyEmerald else ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (operatorEmail != null) "Synced" else "Cloud Sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (operatorEmail != null) SafetyEmerald else ElectricCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineStageRibbon(
    activeStage: PipelineStage,
    protectiveStop: Boolean
) {
    val stages = listOf(
        PipelineStage.TEACH to stringResource(R.string.stage_1_label),
        PipelineStage.INTERPRET to stringResource(R.string.stage_2_label),
        PipelineStage.VALIDATE to stringResource(R.string.stage_3_label),
        PipelineStage.DEPLOY to stringResource(R.string.stage_4_label)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        stages.forEach { (stage, label) ->
            val reached = activeStage.stepNumber >= stage.stepNumber
            val isStopHere = protectiveStop && stage == PipelineStage.VALIDATE
            val chipBg = when {
                isStopHere -> MaterialTheme.colorScheme.errorContainer
                reached -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
            val chipBorder = when {
                isStopHere -> InterlockCrimson
                reached -> ElectricCyan
                else -> MaterialTheme.colorScheme.outline
            }
            val textColor = when {
                isStopHere -> Color.White
                reached -> ElectricCyan
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                color = chipBg,
                border = BorderStroke(1.dp, chipBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusOrErrorBanner(
    errorBanner: String?,
    statusBanner: String?
) {
    if (errorBanner != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            border = BorderStroke(1.dp, InterlockCrimson),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = errorBanner,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    } else if (statusBanner != null) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, SafetyEmerald.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = statusBanner,
                style = MaterialTheme.typography.bodySmall,
                color = SafetyEmerald,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun XrealBottomActionBar(
    state: RoboXrUiState,
    onOneShotRecovery: () -> Unit,
    onApproveDeploy: () -> Unit,
    onFaultInject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canDeploy = state.guardrailReport?.passed == true && state.compiledProgram != null
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onOneShotRecovery,
                border = BorderStroke(1.dp, ElectricCyan),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("one_shot_recovery_button")
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "30s Recovery",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onApproveDeploy,
                enabled = canDeploy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SafetyEmerald,
                    contentColor = Color(0xFF002114),
                    disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .height(48.dp)
                    .testTag("approve_deploy_button")
            ) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (canDeploy) "Approve & Deploy" else "Interlock Locked",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onFaultInject,
                border = BorderStroke(1.dp, InterlockCrimson),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(0.95f)
                    .height(48.dp)
                    .testTag("fault_injection_button")
            ) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = InterlockCrimson, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Fault Test",
                    style = MaterialTheme.typography.labelMedium,
                    color = InterlockCrimson,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

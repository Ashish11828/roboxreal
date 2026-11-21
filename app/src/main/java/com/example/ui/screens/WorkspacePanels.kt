package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.R
import com.example.robotics.AuditLogRecord
import com.example.robotics.ChainVerificationResult
import com.example.robotics.FaultPreset
import com.example.robotics.SavedTaskSpec
import com.example.robotics.Vec3Mm
import com.example.ui.FirestoreUiState
import com.example.ui.PinchTargetMode
import com.example.ui.PlanningUiState
import com.example.ui.RoboXrUiState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.HitlAmber
import com.example.ui.theme.InterlockCrimson
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.MonoTelemetryStyle
import com.example.ui.theme.SafetyEmerald
import com.example.xr.XrealDeviceState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun TeachAndHudPanel(
    state: RoboXrUiState,
    xrealState: XrealDeviceState,
    savedPresetsState: FirestoreUiState<List<SavedTaskSpec>>,
    onToggleImu: (Boolean) -> Unit,
    onRecenterImu: () -> Unit,
    onPinchFromXrealRay: () -> Unit,
    onSelectPinchTarget: (PinchTargetMode) -> Unit,
    onUpdatePick: (Vec3Mm) -> Unit,
    onUpdatePlace: (Vec3Mm) -> Unit,
    onUpdateSpeed: (Double) -> Unit,
    onUpdateIntent: (String) -> Unit,
    onVoiceMicToggle: () -> Unit,
    onToggleOfflineEngine: (Boolean) -> Unit,
    onRunGeminiPlan: () -> Unit,
    onRunOfflinePlan: () -> Unit,
    onSavePreset: () -> Unit,
    onLoadPreset: (SavedTaskSpec) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. XREAL SDK & Android XR Spatial Hardware Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(
                1.dp,
                if (xrealState.xrealUsbConnected || xrealState.externalHudDisplayConnected) {
                    SafetyEmerald
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sensors,
                            contentDescription = "XREAL Sensor Status",
                            tint = if (xrealState.xrealUsbConnected) SafetyEmerald else ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = xrealState.connectedDeviceName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = xrealState.displayResolution,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "3DoF IMU",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = xrealState.imuTrackingActive,
                            onCheckedChange = onToggleImu,
                            modifier = Modifier.testTag("xreal_imu_toggle")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RAYCAST ${xrealState.raycastCursorMm.formatCompact()} mm",
                        style = MonoTelemetryStyle,
                        color = HitlAmber
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (xrealState.imuTrackingActive) {
                            TextButton(onClick = onRecenterImu) {
                                Text("Recenter")
                            }
                        }
                        OutlinedButton(
                            onClick = onPinchFromXrealRay,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, HitlAmber),
                            modifier = Modifier.testTag("pinch_raycast_lock_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MyLocation,
                                contentDescription = "Lock XREAL spatial raycast anchor",
                                tint = HitlAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Pinch ${state.pinchTargetMode.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = HitlAmber
                            )
                        }
                    }
                }
            }
        }

        // 2. Stage 1: Spatial Pick & Place Anchor Controls (mm)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STAGE 1 · SPATIAL ANCHORS (mm)",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.pinchTargetMode == PinchTargetMode.PICK,
                            onClick = { onSelectPinchTarget(PinchTargetMode.PICK) },
                            label = { Text("Pick") },
                            modifier = Modifier.testTag("select_pick_mode_chip")
                        )
                        FilterChip(
                            selected = state.pinchTargetMode == PinchTargetMode.PLACE,
                            onClick = { onSelectPinchTarget(PinchTargetMode.PLACE) },
                            label = { Text("Place") },
                            modifier = Modifier.testTag("select_place_mode_chip")
                        )
                    }
                }

                val activeAnchor = if (state.pinchTargetMode == PinchTargetMode.PICK) {
                    state.pickAnchorMm
                } else {
                    state.placeAnchorMm
                }
                val accent = if (state.pinchTargetMode == PinchTargetMode.PICK) SafetyEmerald else ElectricCyan

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "PICK: ${state.pickAnchorMm.formatCompact()} mm",
                        style = MonoTelemetryStyle,
                        color = SafetyEmerald
                    )
                    Text(
                        text = "PLACE: ${state.placeAnchorMm.formatCompact()} mm",
                        style = MonoTelemetryStyle,
                        color = ElectricCyan
                    )
                }

                AxisSliderRow(
                    axisLabel = "${state.pinchTargetMode.name} X",
                    value = activeAnchor.x,
                    range = 80f..760f,
                    unit = "mm",
                    accentColor = accent,
                    onChange = { newX ->
                        val updated = activeAnchor.copy(x = newX)
                        if (state.pinchTargetMode == PinchTargetMode.PICK) onUpdatePick(updated) else onUpdatePlace(updated)
                    }
                )
                AxisSliderRow(
                    axisLabel = "${state.pinchTargetMode.name} Y",
                    value = activeAnchor.y,
                    range = -340f..340f,
                    unit = "mm",
                    accentColor = accent,
                    onChange = { newY ->
                        val updated = activeAnchor.copy(y = newY)
                        if (state.pinchTargetMode == PinchTargetMode.PICK) onUpdatePick(updated) else onUpdatePlace(updated)
                    }
                )
                AxisSliderRow(
                    axisLabel = "${state.pinchTargetMode.name} Z",
                    value = activeAnchor.z,
                    range = 10f..420f,
                    unit = "mm",
                    accentColor = if (activeAnchor.z < 35.0) InterlockCrimson else accent,
                    onChange = { newZ ->
                        val updated = activeAnchor.copy(z = newZ)
                        if (state.pinchTargetMode == PinchTargetMode.PICK) onUpdatePick(updated) else onUpdatePlace(updated)
                    }
                )
                AxisSliderRow(
                    axisLabel = "Speed",
                    value = state.requestedSpeedMmS,
                    range = 40f..360f,
                    unit = "mm/s",
                    accentColor = if (state.requestedSpeedMmS > 250.0) InterlockCrimson else ElectricCyan,
                    onChange = onUpdateSpeed
                )
            }
        }

        // 3. Stage 2: Voice Intent & Gemini 3.8 Flash / Offline Kotlin Planner
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STAGE 2 · MULTIMODAL PLANNER",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                    IconButton(
                        onClick = onSavePreset,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("save_preset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BookmarkAdd,
                            contentDescription = "Save current anchor preset to Firestore",
                            tint = ElectricCyan
                        )
                    }
                }

                OutlinedTextField(
                    value = state.voiceIntent,
                    onValueChange = onUpdateIntent,
                    label = { Text("Natural Operator Voice / Text Intent") },
                    trailingIcon = {
                        IconButton(
                            onClick = onVoiceMicToggle,
                            modifier = Modifier.testTag("voice_capture_button")
                        ) {
                            Icon(
                                imageVector = if (xrealState.voiceListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (xrealState.voiceListening) "Stop voice capture" else "Start voice capture",
                                tint = if (xrealState.voiceListening) InterlockCrimson else ElectricCyan
                            )
                        }
                    },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_intent_input")
                )

                // Quick Shopfloor Intent Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val quickIntents = listOf(
                        "Clear jammed billet from chuck jaw to reject bin",
                        "Load raw aluminum blank into CNC vise at 160 mm/s",
                        "Unload finished steel flange to inspection tray"
                    )
                    quickIntents.forEach { sample ->
                        AssistChip(
                            onClick = { onUpdateIntent(sample) },
                            label = {
                                Text(
                                    text = sample.take(28) + "…",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRunGeminiPlan,
                        enabled = state.planningState !is PlanningUiState.Planning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF002029)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("plan_gemini_button")
                    ) {
                        if (state.planningState is PlanningUiState.Planning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF002029)
                            )
                        } else {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_plan_gemini),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onRunOfflinePlan,
                        border = BorderStroke(1.dp, HitlAmber),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("plan_offline_button")
                    ) {
                        Icon(Icons.Filled.Memory, contentDescription = null, tint = HitlAmber, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Offline (<5ms)",
                            color = HitlAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Prefer On-Device Offline Engine (air-gapped shopfloor)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.offlineEnginePreferred,
                        onCheckedChange = onToggleOfflineEngine
                    )
                }

                // HRI HUD Guidance Steps from Stage 2 Planner
                state.activeSpec?.let { spec ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = "XREAL HRI HUD GUIDANCE · ${spec.plannerSource.displayLabel} (${spec.planLatencyMs} ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    spec.hriHudGuidance.forEachIndexed { idx, step ->
                        Text(
                            text = "${idx + 1}. $step",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 4. Saved Firestore Presets
        if (savedPresetsState is FirestoreUiState.Success && savedPresetsState.data.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SAVED WORKCELL PRESETS (FIRESTORE)",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                    savedPresetsState.data.take(4).forEach { preset ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.taskName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Pick (${preset.pickX.toInt()},${preset.pickY.toInt()},${preset.pickZ.toInt()}) · ${preset.targetSpeedMmS.toInt()}mm/s",
                                    style = MonoTelemetryStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onLoadPreset(preset) }) {
                                Text("Load")
                            }
                            IconButton(
                                onClick = { onDeletePreset(preset.id) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete preset ${preset.taskName}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SafetyChecklistPanel(
    state: RoboXrUiState,
    modifier: Modifier = Modifier
) {
    val report = state.guardrailReport
    val protectiveStop = report?.protectiveStopActive == true

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (protectiveStop) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.5.dp,
            if (protectiveStop) InterlockCrimson else SafetyEmerald.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (protectiveStop) Icons.Filled.Warning else Icons.Filled.GppGood,
                        contentDescription = "Safety Guardrail Status",
                        tint = if (protectiveStop) InterlockCrimson else SafetyEmerald
                    )
                    Text(
                        text = "STAGE 3 · ISO 10218 GUARDRAILS",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (protectiveStop) InterlockCrimson else SafetyEmerald
                    )
                }
                if (report != null) {
                    Surface(
                        color = Color(0xFF0A1424),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${report.latencyUs} µs (<1ms)",
                            style = MonoTelemetryStyle,
                            color = ElectricCyan,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (protectiveStop) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, InterlockCrimson),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("protective_stop_banner")
                ) {
                    Text(
                        text = stringResource(R.string.protective_stop_banner),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            report?.rules?.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (rule.passed) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = if (rule.passed) "Passed" else "Failed",
                            tint = if (rule.passed) SafetyEmerald else InterlockCrimson,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = rule.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = rule.thresholdLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = rule.measuredLabel,
                        style = MonoTelemetryStyle,
                        color = if (rule.passed) SafetyEmerald else InterlockCrimson
                    )
                }
            }

            if (report != null && report.violations.isNotEmpty()) {
                HorizontalDivider(color = InterlockCrimson.copy(alpha = 0.5f))
                report.violations.take(3).forEach { v ->
                    Text(
                        text = "• [${v.stageLabel}] ${v.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun RoboticsCodePanel(
    state: RoboXrUiState,
    onOpenHitlGate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedCodeTab by remember { mutableIntStateOf(0) }
    val compiled = state.compiledProgram
    val protectiveStop = state.guardrailReport?.protectiveStopActive == true

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STAGE 4 · URSCRIPT & ROS 2 COMPILER",
                    style = MaterialTheme.typography.labelLarge,
                    color = ElectricCyan
                )
                if (compiled != null) {
                    IconButton(
                        onClick = {
                            val textToCopy = if (selectedCodeTab == 0) compiled.urScript else compiled.ros2FollowJointTrajectoryJson
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("RoboXR Program", textToCopy))
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("copy_robotics_code_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy compiled robot code",
                            tint = ElectricCyan
                        )
                    }
                }
            }

            TabRow(
                selectedTabIndex = selectedCodeTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Tab(
                    selected = selectedCodeTab == 0,
                    onClick = { selectedCodeTab = 0 },
                    text = { Text("URScript (UR5e)") }
                )
                Tab(
                    selected = selectedCodeTab == 1,
                    onClick = { selectedCodeTab = 1 },
                    text = { Text("ROS 2 Action Goal") }
                )
            }

            if (protectiveStop || compiled == null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, InterlockCrimson),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "CODE GENERATION BLOCKED BY PROTECTIVE STOP",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Text(
                            text = "Layer 3 deterministic guardrails detected an ISO 10218 violation. Fix the anchor coordinates or speed (<=250mm/s) to unlock URScript & ROS 2 compilation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else {
                val digest = if (selectedCodeTab == 0) compiled.urScriptSha256 else compiled.ros2GoalSha256
                val codeText = if (selectedCodeTab == 0) compiled.urScript else compiled.ros2FollowJointTrajectoryJson

                Text(
                    text = "SHA-256: $digest",
                    style = MonoTelemetryStyle,
                    color = SafetyEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    color = Color(0xFF05080F),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = codeText,
                        style = MonoCodeStyle,
                        color = Color(0xFFD6F3FF),
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Button(
                    onClick = onOpenHitlGate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SafetyEmerald,
                        contentColor = Color(0xFF002114)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("code_panel_approve_deploy_button")
                ) {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Stage 4 Qualified Operator Sign-Off Gate")
                }
            }
        }
    }
}

@Composable
fun AuditChainPanel(
    auditLogsState: FirestoreUiState<List<AuditLogRecord>>,
    chainVerification: ChainVerificationResult,
    localMirrorFileName: String,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss · MMM dd", Locale.US) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (chainVerification.chainValid) SafetyEmerald.copy(alpha = 0.6f) else InterlockCrimson
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = "SHA-256 Audit Chain",
                        tint = if (chainVerification.chainValid) SafetyEmerald else InterlockCrimson
                    )
                    Column {
                        Text(
                            text = "SHA-256 CRYPTOGRAPHIC AUDIT CHAIN",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Firestore + Local JSON ($localMirrorFileName)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    color = if (chainVerification.chainValid) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (chainVerification.chainValid) {
                            "chain_valid (${chainVerification.totalLinks})"
                        } else {
                            "CHAIN TAMPERED"
                        },
                        style = MonoTelemetryStyle,
                        color = if (chainVerification.chainValid) SafetyEmerald else InterlockCrimson,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            when (auditLogsState) {
                is FirestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = ElectricCyan)
                    }
                }
                is FirestoreUiState.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = auditLogsState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                is FirestoreUiState.Success -> {
                    val logs = auditLogsState.data.reversed()
                    if (logs.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VerifiedUser,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "No UR5e deployments audited yet",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Approve a validated trajectory through the Stage 4 HITL Operator Gate to append the genesis SHA-256 link.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        logs.forEach { rec ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = rec.taskName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${"%.1f".format(rec.recoveryDurationMs / 1000.0)}s",
                                            style = MonoTelemetryStyle,
                                            color = SafetyEmerald
                                        )
                                    }
                                    val ts = rec.createdAt?.toDate()?.let { timeFormatter.format(it) } ?: "Syncing..."
                                    Text(
                                        text = "Operator ${rec.operatorBadgeId} (${rec.operatorEmail}) · $ts",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Pick ${rec.pickCoordsMm} -> Place ${rec.placeCoordsMm} · ${rec.targetSpeedMmS.toInt()}mm/s · ${rec.guardrailLatencyUs}µs",
                                        style = MonoTelemetryStyle,
                                        color = ElectricCyan
                                    )
                                    Text(
                                        text = "HASH: ${rec.recordHash.take(24)}…  PREV: ${rec.previousHash.take(12)}…",
                                        style = MonoCodeStyle,
                                        color = SafetyEmerald
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HitlOperatorGateDialog(
    state: RoboXrUiState,
    operatorEmail: String,
    onBadgeChange: (String) -> Unit,
    onConfirmClearanceChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onApproveAndDeploy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = HitlAmber,
        icon = {
            Icon(
                imageVector = Icons.Filled.VerifiedUser,
                contentDescription = "Stage 4 Operator Gate",
                tint = HitlAmber,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.hitl_gate_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.hitl_gate_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Operator: $operatorEmail",
                            style = MonoTelemetryStyle,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Task: ${state.activeSpec?.taskName ?: "CNC Recovery"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Pick ${state.pickAnchorMm.formatCompact()} -> Place ${state.placeAnchorMm.formatCompact()} mm",
                            style = MonoTelemetryStyle,
                            color = SafetyEmerald
                        )
                    }
                }

                OutlinedTextField(
                    value = state.operatorBadgeId,
                    onValueChange = onBadgeChange,
                    label = { Text("Qualified Operator Badge ID") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("operator_badge_input")
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = state.physicalClearanceConfirmed,
                        onCheckedChange = onConfirmClearanceChange,
                        modifier = Modifier.testTag("physical_clearance_checkbox")
                    )
                    Text(
                        text = "I verify the physical CNC workcell is clear of personnel and approve UR5e URScript & ROS 2 execution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApproveAndDeploy,
                enabled = state.physicalClearanceConfirmed && state.operatorBadgeId.trim().length >= 2 && !state.deploying,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SafetyEmerald,
                    contentColor = Color(0xFF002114)
                ),
                modifier = Modifier.testTag("confirm_hitl_deploy_button")
            ) {
                if (state.deploying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Sign-Off & Dispatch")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun FaultInjectionDialog(
    onDismiss: () -> Unit,
    onSelectFault: (FaultPreset) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Fault Injection",
                tint = InterlockCrimson,
                modifier = Modifier.size(30.dp)
            )
        },
        title = {
            Text(
                text = "ISO 10218 Fault Injection Test",
                style = MaterialTheme.typography.titleLarge,
                color = InterlockCrimson
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Inject an intentional safety breach to prove the deterministic Stage 3 Protective Stop Interlock blocks codegen before motor actuation:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FaultPreset.entries.forEach { preset ->
                    OutlinedButton(
                        onClick = { onSelectFault(preset) },
                        border = BorderStroke(1.dp, InterlockCrimson.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fault_preset_${preset.name.lowercase()}")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = InterlockCrimson
                            )
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun AxisSliderRow(
    axisLabel: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    accentColor: Color,
    onChange: (Double) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = axisLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${value.toInt()} $unit",
            style = MonoTelemetryStyle,
            color = accentColor,
            modifier = Modifier.width(64.dp)
        )
    }
}

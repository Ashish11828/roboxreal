package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.robotics.ElectrochromicLevel
import com.example.robotics.GuardrailReport
import com.example.robotics.ManufacturingCell
import com.example.robotics.SafetyGuardrails
import com.example.robotics.StructuredTaskSpec
import com.example.robotics.Vec3Mm
import com.example.robotics.VisorInteractionMode
import com.example.ui.PinchTargetMode
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.HitlAmber
import com.example.ui.theme.InterlockCrimson
import com.example.ui.theme.MonoTelemetryStyle
import com.example.ui.theme.OpticalPureBlack
import com.example.ui.theme.SafetyEmerald
import com.example.xr.XrealDeviceState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class ProjectedPoint(val x: Float, val y: Float, val depth: Float)

/**
 * Interactive XREAL Optical See-Through Manufacturing Hub Visor & 3D UR5e Digital Twin.
 * Supports:
 *  - PINCH DRAG mode: live touch/drag across the 3D CNC workcell moves the glowing Pick/Place
 *    hologram in real time while the UR5e cobot tracks it via analytic IK and ISO 10218 checks run <1ms!
 *  - 3D ORBIT mode: single-finger orbit + two-finger pinch-to-zoom + camera presets (ISO, TOP, CHUCK).
 *  - On-visor Z-elevation +/- step controls with live 35mm safety plane readout.
 *  - XREAL Birdbath optics overlay: corner calibration brackets, IMU pitch horizon, electrochromic
 *    dimming (0% Optical Clear / 50% Tint / 100% Twin), live CNC spindle/door/chuck status,
 *    animated billet in gripper jaws, and interactive 6-stage waypoint scrubber.
 */
@Composable
fun Ur5eDigitalTwinCanvas(
    cell: ManufacturingCell,
    spec: StructuredTaskSpec?,
    guardrailReport: GuardrailReport?,
    pickAnchorMm: Vec3Mm,
    placeAnchorMm: Vec3Mm,
    xrealState: XrealDeviceState,
    pinchTargetMode: PinchTargetMode,
    visorInteractionMode: VisorInteractionMode,
    electrochromicLevel: ElectrochromicLevel,
    isPlaying: Boolean,
    manualStageIndex: Int?,
    onTogglePlay: () -> Unit,
    onSelectStageIndex: (Int) -> Unit,
    onSetInteractionMode: (VisorInteractionMode) -> Unit,
    onSelectPinchTarget: (PinchTargetMode) -> Unit,
    onCycleElectrochromic: () -> Unit,
    onMoveActiveAnchor: (Vec3Mm) -> Unit,
    onStepActiveAnchorZ: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var yawDeg by remember { mutableFloatStateOf(-40f) }
    var pitchDeg by remember { mutableFloatStateOf(28f) }
    var zoomScale by remember { mutableFloatStateOf(1.05f) }

    val infiniteTransition = rememberInfiniteTransition(label = "ur5e_twin_cycle")
    val animProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "trajectory_progress"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hud_pulse"
    )

    val waypoints = spec?.waypoints.orEmpty()
    val activeAnchor = if (pinchTargetMode == PinchTargetMode.PICK) pickAnchorMm else placeAnchorMm

    // Compute live progress or frozen manual stage pose
    val effectiveProgress = when {
        manualStageIndex != null && waypoints.size >= 2 ->
            manualStageIndex.toFloat() / (waypoints.size - 1).toFloat()
        isPlaying -> animProgress
        else -> 0.42f
    }

    val currentStageIdx = remember(waypoints, effectiveProgress) {
        if (waypoints.size >= 2) {
            (effectiveProgress * (waypoints.size - 1)).toInt().coerceIn(0, waypoints.lastIndex)
        } else 0
    }

    val currentTcp = remember(waypoints, effectiveProgress, pickAnchorMm) {
        if (waypoints.size >= 2) {
            val scaled = effectiveProgress * (waypoints.size - 1)
            val idx = scaled.toInt().coerceIn(0, waypoints.size - 2)
            val localT = (scaled - idx).toDouble()
            val a = waypoints[idx].positionMm
            val b = waypoints[idx + 1].positionMm
            Vec3Mm(
                x = a.x + (b.x - a.x) * localT,
                y = a.y + (b.y - a.y) * localT,
                z = a.z + (b.z - a.z) * localT
            )
        } else {
            pickAnchorMm
        }
    }

    // Carrying aluminum billet during stages 2 (Pick Grasp) through 5 (Place Release)
    val holdingBillet = currentStageIdx in 2..4
    val currentJoints = remember(currentTcp) { SafetyGuardrails.solveUr5eIk(currentTcp) }
    val protectiveStop = guardrailReport?.protectiveStopActive == true

    val borderColor = when {
        protectiveStop -> InterlockCrimson
        electrochromicLevel == ElectrochromicLevel.OPTICAL_CLEAR -> ElectricCyan
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    val bgBrush = when (electrochromicLevel) {
        ElectrochromicLevel.OPTICAL_CLEAR -> Brush.verticalGradient(listOf(OpticalPureBlack, OpticalPureBlack))
        ElectrochromicLevel.SHOPFLOOR_TINT -> Brush.radialGradient(
            listOf(Color(0xFF0A1626), Color(0xFF03060B))
        )
        ElectrochromicLevel.FULL_TWIN -> Brush.radialGradient(
            listOf(Color(0xFF12233E), Color(0xFF070C16))
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(bgBrush)
                .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
                .testTag("ur5e_digital_twin_canvas")
        ) {
            // Interactive 3D Spatial Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(visorInteractionMode, pinchTargetMode, pickAnchorMm, placeAnchorMm) {
                        if (visorInteractionMode == VisorInteractionMode.ORBIT_3D) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                yawDeg = (yawDeg + pan.x * 0.42f) % 360f
                                pitchDeg = (pitchDeg + pan.y * 0.34f).coerceIn(8f, 82f)
                                zoomScale = (zoomScale * zoom).coerceIn(0.65f, 1.95f)
                            }
                        } else {
                            // Live PINCH DRAG: dragging directly moves the active Pick or Place 3D hologram!
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val current = if (pinchTargetMode == PinchTargetMode.PICK) pickAnchorMm else placeAnchorMm
                                val yawRad = Math.toRadians(yawDeg.toDouble())
                                // Convert screen drag into worktable X/Y motion aligned with current camera yaw
                                val scaleMmPerPx = 1.65 / zoomScale
                                val dxWorld = (dragAmount.x * cos(yawRad) - dragAmount.y * sin(yawRad)) * scaleMmPerPx
                                val dyWorld = (-dragAmount.x * sin(yawRad) - dragAmount.y * cos(yawRad)) * scaleMmPerPx
                                onMoveActiveAnchor(
                                    Vec3Mm(
                                        x = (current.x + dxWorld).coerceIn(80.0, 760.0),
                                        y = (current.y + dyWorld).coerceIn(-380.0, 380.0),
                                        z = current.z
                                    )
                                )
                            }
                        }
                    }
                    .pointerInput(pinchTargetMode, pickAnchorMm, placeAnchorMm) {
                        detectTapGestures { offset ->
                            val normX = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0.06f, 0.94f)
                            val normY = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0.08f, 0.92f)
                            val mappedX = 110.0 + normX * 540.0
                            val mappedY = 300.0 - normY * 560.0
                            val keepZ = if (pinchTargetMode == PinchTargetMode.PICK) pickAnchorMm.z else placeAnchorMm.z
                            onMoveActiveAnchor(Vec3Mm(mappedX, mappedY, keepZ))
                        }
                    }
            ) {
                val effectiveYaw = yawDeg + (if (xrealState.imuTrackingActive) xrealState.headYawDeg * 0.6f else 0f)
                val effectivePitch = (pitchDeg + (if (xrealState.imuTrackingActive) xrealState.headPitchDeg * 0.35f else 0f))
                    .coerceIn(6f, 84f)

                val yawRad = Math.toRadians(effectiveYaw.toDouble())
                val pitchRad = Math.toRadians(effectivePitch.toDouble())
                val centerX = size.width * 0.47f
                val centerY = size.height * 0.63f
                val baseScale = (minOf(size.width, size.height) / 1040f) * zoomScale

                fun project(p: Vec3Mm): ProjectedPoint {
                    val wx = p.x - 260.0
                    val wy = p.y - 20.0
                    val wz = p.z - 80.0
                    val rx = wx * cos(yawRad) - wy * sin(yawRad)
                    val ry = wx * sin(yawRad) + wy * cos(yawRad)
                    val py = ry * cos(pitchRad) - wz * sin(pitchRad)
                    val pz = ry * sin(pitchRad) + wz * cos(pitchRad)
                    val persp = 1480.0 / (1480.0 + py)
                    val sx = centerX + (rx * baseScale * persp).toFloat()
                    val sy = centerY - (pz * baseScale * persp).toFloat()
                    return ProjectedPoint(sx, sy, py.toFloat())
                }

                // 1. Optical XREAL Corner Reticle Brackets & Pitch Horizon
                drawXrealOpticalHudFrame(
                    protectiveStop = protectiveStop,
                    headPitchDeg = xrealState.headPitchDeg,
                    pulseAlpha = pulseAlpha
                )

                // 2. Floor millimeter worktable grid (Z = 0)
                drawWorkcellGrid(::project)

                // 3. 820mm UR5e Radial Reach Boundary Ring (Z = 35mm)
                drawReachRing(::project, radiusMm = 820.0, zMm = 35.0)

                // 4. 35mm Minimum Z Safety Plane
                drawSafetyPlane35mm(::project, protectiveStop)

                // 5. CNC Machining Enclosure, Sliding Door, 3-Jaw Chuck & Output Tray
                drawCncMachineCell(::project, cell)

                // 6. Red Translucent Fixture Keep-Out Box [X:310..395, Y:65..145, Z<=160]
                drawKeepOutBox(::project, protectiveStop, pulseAlpha)

                // 7. 6-Stage Trajectory Spline & Stage Waypoint Nodes
                if (waypoints.size >= 2) {
                    val pathColor = if (protectiveStop) InterlockCrimson else ElectricCyan
                    val path = Path()
                    waypoints.forEachIndexed { i, wp ->
                        val proj = project(wp.positionMm)
                        if (i == 0) path.moveTo(proj.x, proj.y) else path.lineTo(proj.x, proj.y)
                    }
                    drawPath(
                        path = path,
                        color = pathColor.copy(alpha = 0.85f),
                        style = Stroke(
                            width = 3.2f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                        )
                    )
                    // Highlight completed portion of cycle in bright emerald
                    val activePath = Path()
                    val segEnd = (currentStageIdx + 1).coerceAtMost(waypoints.lastIndex)
                    for (i in 0..segEnd) {
                        val proj = project(waypoints[i].positionMm)
                        if (i == 0) activePath.moveTo(proj.x, proj.y) else activePath.lineTo(proj.x, proj.y)
                    }
                    drawPath(
                        path = activePath,
                        color = if (protectiveStop) InterlockCrimson else SafetyEmerald.copy(alpha = 0.9f),
                        style = Stroke(width = 3.6f, cap = StrokeCap.Round)
                    )

                    waypoints.forEachIndexed { idx, wp ->
                        val p = project(wp.positionMm)
                        val isCurrent = idx == currentStageIdx
                        drawCircle(
                            color = if (isCurrent) HitlAmber else pathColor,
                            radius = if (isCurrent) 6.5f else 4f,
                            center = Offset(p.x, p.y)
                        )
                    }
                }

                // 8. Interactive Glowing Pick & Place Spatial Anchors
                drawAnchorMarker(
                    project = ::project,
                    anchorMm = pickAnchorMm,
                    color = SafetyEmerald,
                    isSelected = pinchTargetMode == PinchTargetMode.PICK,
                    pulseAlpha = pulseAlpha
                )
                drawAnchorMarker(
                    project = ::project,
                    anchorMm = placeAnchorMm,
                    color = ElectricCyan,
                    isSelected = pinchTargetMode == PinchTargetMode.PLACE,
                    pulseAlpha = pulseAlpha
                )

                // 9. XREAL Live Spatial Gaze Raycast Reticle
                val reticlePt = project(xrealState.raycastCursorMm)
                drawCircle(
                    color = HitlAmber.copy(alpha = 0.8f),
                    radius = 13f,
                    center = Offset(reticlePt.x, reticlePt.y),
                    style = Stroke(width = 1.8f)
                )
                drawLine(HitlAmber, Offset(reticlePt.x - 19f, reticlePt.y), Offset(reticlePt.x + 19f, reticlePt.y), 1.4f)
                drawLine(HitlAmber, Offset(reticlePt.x, reticlePt.y - 19f), Offset(reticlePt.x, reticlePt.y + 19f), 1.4f)

                // 10. Articulated UR5e 6-DOF Cobot Arm + Animated Aluminum Billet in Gripper
                drawUr5eCobotArm(
                    project = ::project,
                    tcpMm = currentTcp,
                    j1Rad = currentJoints.shoulderPanRad,
                    j2Rad = currentJoints.shoulderLiftRad,
                    holdingBillet = holdingBillet,
                    protectiveStop = protectiveStop
                )

                // 11. Pulsing Crimson Collision Interlock Markers
                guardrailReport?.violatingPointsMm?.forEach { badPt ->
                    val p = project(badPt)
                    drawCircle(
                        color = InterlockCrimson.copy(alpha = pulseAlpha * 0.45f),
                        radius = 24f,
                        center = Offset(p.x, p.y)
                    )
                    drawCircle(
                        color = InterlockCrimson,
                        radius = 10f,
                        center = Offset(p.x, p.y),
                        style = Stroke(width = 3f)
                    )
                }
            }

            // TOP-LEFT: Live XREAL Manufacturing Hub Cell Telemetry HUD
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = Color(0xCC050912),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (protectiveStop) InterlockCrimson else ElectricCyan.copy(alpha = 0.7f)
                        )
                    ) {
                        Text(
                            text = "${cell.cellId} · ${cell.machineName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (protectiveStop) InterlockCrimson else ElectricCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        color = Color(0xCC050912),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = "${cell.spindleRpm} RPM · ${if (cell.doorOpen) "DOOR OPEN" else "DOOR LOCKED"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cell.doorOpen) HitlAmber else SafetyEmerald,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }
                Text(
                    text = "TCP ${currentTcp.formatCompact()} mm | J1=${"%.2f".format(currentJoints.shoulderPanRad)} J2=${"%.2f".format(currentJoints.shoulderLiftRad)} | ${if (holdingBillet) "GRIP: CLOSED" else "GRIP: OPEN"}",
                    style = MonoTelemetryStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .background(Color(0xAA050912), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // TOP-RIGHT: Interaction Mode (Pinch Drag vs 3D Orbit) + Electrochromic Lens Dimmer
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        val next = if (visorInteractionMode == VisorInteractionMode.PINCH_DRAG) {
                            VisorInteractionMode.ORBIT_3D
                        } else {
                            VisorInteractionMode.PINCH_DRAG
                        }
                        onSetInteractionMode(next)
                    },
                    color = Color(0xCC060C18),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (visorInteractionMode == VisorInteractionMode.PINCH_DRAG) SafetyEmerald else ElectricCyan
                    ),
                    modifier = Modifier.testTag("visor_interaction_mode_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (visorInteractionMode == VisorInteractionMode.PINCH_DRAG) {
                                Icons.Filled.TouchApp
                            } else {
                                Icons.Filled.ControlCamera
                            },
                            contentDescription = "Switch visor interaction mode",
                            tint = if (visorInteractionMode == VisorInteractionMode.PINCH_DRAG) SafetyEmerald else ElectricCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = visorInteractionMode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (visorInteractionMode == VisorInteractionMode.PINCH_DRAG) SafetyEmerald else ElectricCyan
                        )
                    }
                }

                Surface(
                    onClick = onCycleElectrochromic,
                    color = Color(0xCC060C18),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("electrochromic_cycle_button")
                ) {
                    Text(
                        text = electrochromicLevel.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            // RIGHT EDGE: Interactive On-Visor Z-Elevation Step Rail
            Surface(
                color = Color(0xCC060C18),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (activeAnchor.z < 35.0) InterlockCrimson else ElectricCyan.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp)
                ) {
                    IconButton(
                        onClick = { onStepActiveAnchorZ((activeAnchor.z + 20.0).coerceAtMost(420.0)) },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("visor_z_up_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Raise anchor Z height by 20mm",
                            tint = SafetyEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "Z",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${activeAnchor.z.toInt()}",
                        style = MonoTelemetryStyle,
                        color = if (activeAnchor.z < 35.0) InterlockCrimson else ElectricCyan
                    )
                    IconButton(
                        onClick = { onStepActiveAnchorZ((activeAnchor.z - 20.0).coerceAtLeast(15.0)) },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("visor_z_down_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = "Lower anchor Z height by 20mm",
                            tint = HitlAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // BOTTOM BAR INSIDE VISOR: Quick Pick/Place Switch + Camera Presets + Play/Pause
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VisorMiniChip(
                        label = "PICK",
                        accent = SafetyEmerald,
                        selected = pinchTargetMode == PinchTargetMode.PICK,
                        onClick = { onSelectPinchTarget(PinchTargetMode.PICK) }
                    )
                    VisorMiniChip(
                        label = "PLACE",
                        accent = ElectricCyan,
                        selected = pinchTargetMode == PinchTargetMode.PLACE,
                        onClick = { onSelectPinchTarget(PinchTargetMode.PLACE) }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CameraPresetChip("ISO") { yawDeg = -40f; pitchDeg = 28f; zoomScale = 1.05f }
                    CameraPresetChip("TOP") { yawDeg = 0f; pitchDeg = 80f; zoomScale = 1.1f }
                    CameraPresetChip("CHUCK") { yawDeg = -18f; pitchDeg = 18f; zoomScale = 1.35f }
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("twin_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause UR5e animation" else "Play UR5e animation",
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // INTERACTIVE 6-STAGE WAYPOINT SCRUBBER directly below the XREAL visor
        if (waypoints.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                waypoints.forEachIndexed { idx, wp ->
                    val active = idx == currentStageIdx
                    Surface(
                        onClick = { onSelectStageIndex(idx) },
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (active) ElectricCyan else MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("stage_scrub_$idx")
                    ) {
                        Text(
                            text = "S${idx + 1} ${wp.label.take(14)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisorMiniChip(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) accent.copy(alpha = 0.22f) else Color(0xBB060C18),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) accent else accent.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun CameraPresetChip(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun DrawScope.drawXrealOpticalHudFrame(
    protectiveStop: Boolean,
    headPitchDeg: Float,
    pulseAlpha: Float
) {
    val hudColor = if (protectiveStop) InterlockCrimson.copy(alpha = pulseAlpha) else ElectricCyan.copy(alpha = 0.38f)
    val margin = 12f
    val bracketLen = 22f
    val w = size.width
    val h = size.height

    // 4 Corner Optical Birdbath Calibration Brackets
    // Top-left
    drawLine(hudColor, Offset(margin, margin), Offset(margin + bracketLen, margin), 2f)
    drawLine(hudColor, Offset(margin, margin), Offset(margin, margin + bracketLen), 2f)
    // Top-right
    drawLine(hudColor, Offset(w - margin, margin), Offset(w - margin - bracketLen, margin), 2f)
    drawLine(hudColor, Offset(w - margin, margin), Offset(w - margin, margin + bracketLen), 2f)
    // Bottom-left
    drawLine(hudColor, Offset(margin, h - margin), Offset(margin + bracketLen, h - margin), 2f)
    drawLine(hudColor, Offset(margin, h - margin), Offset(margin, h - margin - bracketLen), 2f)
    // Bottom-right
    drawLine(hudColor, Offset(w - margin, h - margin), Offset(w - margin - bracketLen, h - margin), 2f)
    drawLine(hudColor, Offset(w - margin, h - margin), Offset(w - margin, h - margin - bracketLen), 2f)

    // Subtle Optical Horizon Ticks shifted by real IMU Pitch
    val horizonY = (h * 0.5f) + (headPitchDeg * 1.2f).coerceIn(-40f, 40f)
    val horizonColor = ElectricCyan.copy(alpha = 0.16f)
    drawLine(horizonColor, Offset(w * 0.30f, horizonY), Offset(w * 0.42f, horizonY), 1.2f)
    drawLine(horizonColor, Offset(w * 0.58f, horizonY), Offset(w * 0.70f, horizonY), 1.2f)
}

private fun DrawScope.drawWorkcellGrid(project: (Vec3Mm) -> ProjectedPoint) {
    val gridColor = Color(0xFF1E324D).copy(alpha = 0.5f)
    for (x in -200..700 step 100) {
        val a = project(Vec3Mm(x.toDouble(), -350.0, 0.0))
        val b = project(Vec3Mm(x.toDouble(), 350.0, 0.0))
        drawLine(gridColor, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 1f)
    }
    for (y in -300..300 step 100) {
        val a = project(Vec3Mm(-200.0, y.toDouble(), 0.0))
        val b = project(Vec3Mm(700.0, y.toDouble(), 0.0))
        drawLine(gridColor, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 1f)
    }
}

private fun DrawScope.drawReachRing(
    project: (Vec3Mm) -> ProjectedPoint,
    radiusMm: Double,
    zMm: Double
) {
    val steps = 40
    val ringPath = Path()
    for (i in 0..steps) {
        val theta = (i.toDouble() / steps) * 2.0 * PI
        val pt = project(Vec3Mm(cos(theta) * radiusMm, sin(theta) * radiusMm, zMm))
        if (i == 0) ringPath.moveTo(pt.x, pt.y) else ringPath.lineTo(pt.x, pt.y)
    }
    drawPath(
        path = ringPath,
        color = ElectricCyan.copy(alpha = 0.24f),
        style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    )
}

private fun DrawScope.drawSafetyPlane35mm(
    project: (Vec3Mm) -> ProjectedPoint,
    protectiveStop: Boolean
) {
    val corners = listOf(
        project(Vec3Mm(0.0, -300.0, 35.0)),
        project(Vec3Mm(660.0, -300.0, 35.0)),
        project(Vec3Mm(660.0, 300.0, 35.0)),
        project(Vec3Mm(0.0, 300.0, 35.0))
    )
    val planePath = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        lineTo(corners[1].x, corners[1].y)
        lineTo(corners[2].x, corners[2].y)
        lineTo(corners[3].x, corners[3].y)
        close()
    }
    val planeColor = if (protectiveStop) HitlAmber else ElectricCyan
    drawPath(planePath, color = planeColor.copy(alpha = 0.06f))
    drawPath(planePath, color = planeColor.copy(alpha = 0.35f), style = Stroke(width = 1.4f))
}

private fun DrawScope.drawCncMachineCell(
    project: (Vec3Mm) -> ProjectedPoint,
    cell: ManufacturingCell
) {
    // CNC Enclosure Frame behind Pick zone
    drawWireframeBox(
        project = project,
        minPt = Vec3Mm(160.0, 165.0, 0.0),
        maxPt = Vec3Mm(455.0, 340.0, 360.0),
        strokeColor = if (cell.doorOpen) Color(0xFF64748B) else Color(0xFF38BDF8).copy(alpha = 0.55f),
        fillColor = Color(0x1F334155),
        strokeWidth = 1.8f
    )
    // CNC 3-Jaw Spindle Chuck
    val chuckCenter = project(Vec3Mm(240.0, 210.0, 110.0))
    drawCircle(
        color = if (cell.spindleRpm > 0) ElectricCyan else Color(0xFF94A3B8),
        radius = 17f,
        center = Offset(chuckCenter.x, chuckCenter.y),
        style = Stroke(width = 2.6f)
    )
    // Output / Reject / QC Tray near Place zone
    drawWireframeBox(
        project = project,
        minPt = Vec3Mm(460.0, -230.0, 0.0),
        maxPt = Vec3Mm(590.0, -90.0, 45.0),
        strokeColor = Color(0xFF38BDF8).copy(alpha = 0.68f),
        fillColor = Color(0x1A00E5FF),
        strokeWidth = 1.6f
    )
}

private fun DrawScope.drawKeepOutBox(
    project: (Vec3Mm) -> ProjectedPoint,
    protectiveStop: Boolean,
    pulseAlpha: Float
) {
    drawWireframeBox(
        project = project,
        minPt = Vec3Mm(SafetyGuardrails.KEEP_OUT_X_MIN, SafetyGuardrails.KEEP_OUT_Y_MIN, 0.0),
        maxPt = Vec3Mm(
            SafetyGuardrails.KEEP_OUT_X_MAX,
            SafetyGuardrails.KEEP_OUT_Y_MAX,
            SafetyGuardrails.KEEP_OUT_Z_MAX
        ),
        strokeColor = InterlockCrimson,
        fillColor = InterlockCrimson.copy(alpha = if (protectiveStop) pulseAlpha * 0.48f else 0.16f),
        strokeWidth = if (protectiveStop) 3.0f else 2.0f
    )
}

private fun DrawScope.drawWireframeBox(
    project: (Vec3Mm) -> ProjectedPoint,
    minPt: Vec3Mm,
    maxPt: Vec3Mm,
    strokeColor: Color,
    fillColor: Color,
    strokeWidth: Float
) {
    val v = listOf(
        project(Vec3Mm(minPt.x, minPt.y, minPt.z)),
        project(Vec3Mm(maxPt.x, minPt.y, minPt.z)),
        project(Vec3Mm(maxPt.x, maxPt.y, minPt.z)),
        project(Vec3Mm(minPt.x, maxPt.y, minPt.z)),
        project(Vec3Mm(minPt.x, minPt.y, maxPt.z)),
        project(Vec3Mm(maxPt.x, minPt.y, maxPt.z)),
        project(Vec3Mm(maxPt.x, maxPt.y, maxPt.z)),
        project(Vec3Mm(minPt.x, maxPt.y, maxPt.z))
    )
    val topFace = Path().apply {
        moveTo(v[4].x, v[4].y); lineTo(v[5].x, v[5].y); lineTo(v[6].x, v[6].y); lineTo(v[7].x, v[7].y); close()
    }
    drawPath(topFace, color = fillColor)
    val frontFace = Path().apply {
        moveTo(v[0].x, v[0].y); lineTo(v[1].x, v[1].y); lineTo(v[5].x, v[5].y); lineTo(v[4].x, v[4].y); close()
    }
    drawPath(frontFace, color = fillColor)
    val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
    edges.forEach { (a, b) ->
        drawLine(strokeColor, Offset(v[a].x, v[a].y), Offset(v[b].x, v[b].y), strokeWidth = strokeWidth)
    }
}

private fun DrawScope.drawAnchorMarker(
    project: (Vec3Mm) -> ProjectedPoint,
    anchorMm: Vec3Mm,
    color: Color,
    isSelected: Boolean,
    pulseAlpha: Float
) {
    val ground = project(Vec3Mm(anchorMm.x, anchorMm.y, 0.0))
    val pt = project(anchorMm)
    drawLine(
        color = color.copy(alpha = 0.6f),
        start = Offset(ground.x, ground.y),
        end = Offset(pt.x, pt.y),
        strokeWidth = 1.6f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    )
    if (isSelected) {
        drawCircle(
            color = color.copy(alpha = pulseAlpha * 0.4f),
            radius = 24f,
            center = Offset(pt.x, pt.y)
        )
        drawCircle(
            color = color,
            radius = 16f,
            center = Offset(pt.x, pt.y),
            style = Stroke(width = 1.8f)
        )
    }
    drawCircle(color = color.copy(alpha = 0.28f), radius = 13f, center = Offset(pt.x, pt.y))
    drawCircle(color = color, radius = 7.5f, center = Offset(pt.x, pt.y))
    drawCircle(color = Color.White, radius = 2.6f, center = Offset(pt.x, pt.y))
}

private fun DrawScope.drawUr5eCobotArm(
    project: (Vec3Mm) -> ProjectedPoint,
    tcpMm: Vec3Mm,
    j1Rad: Double,
    j2Rad: Double,
    holdingBillet: Boolean,
    protectiveStop: Boolean
) {
    val base = Vec3Mm(0.0, 0.0, 0.0)
    val shoulder = Vec3Mm(0.0, 0.0, 162.5)
    val l1 = 425.0
    val r1 = cos(-j2Rad) * l1
    val z1 = 162.5 + sin(-j2Rad) * l1
    val elbow = Vec3Mm(r1 * cos(j1Rad), r1 * sin(j1Rad), z1.coerceAtLeast(140.0))
    val wrist = Vec3Mm(tcpMm.x, tcpMm.y, tcpMm.z + 95.0)
    val tcp = tcpMm

    val pBase = project(base)
    val pShoulder = project(shoulder)
    val pElbow = project(elbow)
    val pWrist = project(wrist)
    val pTcp = project(tcp)

    val armMetal = Color(0xFFCBD5E1)
    val jointAccent = if (protectiveStop) InterlockCrimson else ElectricCyan

    drawCircle(color = Color(0xFF334155), radius = 18f, center = Offset(pBase.x, pBase.y))
    drawCircle(color = jointAccent, radius = 18f, center = Offset(pBase.x, pBase.y), style = Stroke(2.2f))

    drawLine(armMetal, Offset(pBase.x, pBase.y), Offset(pShoulder.x, pShoulder.y), strokeWidth = 11f, cap = StrokeCap.Round)
    drawLine(armMetal, Offset(pShoulder.x, pShoulder.y), Offset(pElbow.x, pElbow.y), strokeWidth = 9.5f, cap = StrokeCap.Round)
    drawLine(Color(0xFFE2E8F0), Offset(pElbow.x, pElbow.y), Offset(pWrist.x, pWrist.y), strokeWidth = 7.5f, cap = StrokeCap.Round)
    drawLine(jointAccent, Offset(pWrist.x, pWrist.y), Offset(pTcp.x, pTcp.y), strokeWidth = 4.5f, cap = StrokeCap.Round)

    listOf(pShoulder, pElbow, pWrist).forEach { jt ->
        drawCircle(color = Color(0xFF0F172A), radius = 8.5f, center = Offset(jt.x, jt.y))
        drawCircle(color = jointAccent, radius = 8.5f, center = Offset(jt.x, jt.y), style = Stroke(2.5f))
    }

    // Parallel Gripper Jaws + Animated Aluminum Billet when grasped
    val jawSpread = if (holdingBillet) 5f else 11f
    drawLine(jointAccent, Offset(pTcp.x - jawSpread, pTcp.y - 6f), Offset(pTcp.x - jawSpread, pTcp.y + 8f), 2.5f)
    drawLine(jointAccent, Offset(pTcp.x + jawSpread, pTcp.y - 6f), Offset(pTcp.x + jawSpread, pTcp.y + 8f), 2.5f)
    if (holdingBillet) {
        drawCircle(color = HitlAmber, radius = 6f, center = Offset(pTcp.x, pTcp.y + 2f))
        drawCircle(color = Color.White, radius = 2.5f, center = Offset(pTcp.x, pTcp.y + 2f))
    }
}

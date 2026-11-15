package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.AicpTrafficRateSample
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.colorFabInactiveDark
import com.v2ray.ang.ui.compose.colorFabInactiveLight
import kotlinx.coroutines.launch

@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    smartConnectEnabled: Boolean,
    isDarkTheme: Boolean,
    isRefreshingConfigs: Boolean,
    isTesting: Boolean,
    activeOperation: VpnOperation?,
    operationProgress: OperationProgress?,
    trafficHistory: List<AicpTrafficRateSample>,
    onAction: (MainAction) -> Unit
) {
    val scope = rememberCoroutineScope()
    val rotationAnim = remember { Animatable(0f) }
    val pulseTransition = rememberInfiniteTransition(label = "aicp-connect-pulse")
    val connectDescription = stringResource(if (isRunning) R.string.acc_stop else R.string.acc_start)
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aicp-connect-pulse-alpha"
    )

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            rotationAnim.snapTo(0f)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            AppDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 94.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 76.dp)) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        modifier = Modifier
                            .clickable(onClick = { onAction(MainAction.TestCurrentServer) })
                            .semantics { contentDescription = displayText }
                    )
                    if (isRunning && trafficHistory.isNotEmpty()) {
                        val latest = trafficHistory.last()
                        val sharedScaleMax = trafficHistory.flatMap { sample ->
                            listOf(
                                sample.tunnelUploadBytesPerSecond,
                                sample.tunnelDownloadBytesPerSecond,
                                sample.directUploadBytesPerSecond,
                                sample.directDownloadBytesPerSecond,
                            )
                        }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                        TrafficRateGraph(
                            title = stringResource(R.string.traffic_graph_tunnel),
                            upload = latest.tunnelUploadBytesPerSecond,
                            download = latest.tunnelDownloadBytesPerSecond,
                            history = trafficHistory,
                            tunnel = true,
                            scaleMax = sharedScaleMax,
                        )
                        TrafficRateGraph(
                            title = stringResource(R.string.traffic_graph_direct),
                            upload = latest.directUploadBytesPerSecond,
                            download = latest.directDownloadBytesPerSecond,
                            history = trafficHistory,
                            tunnel = false,
                            scaleMax = sharedScaleMax,
                        )
                    }
                }
            }
            AppDivider()
            val busy = activeOperation != null || isTesting || isRefreshingConfigs
            // Two three-column rows keep every operation visible and give each control a full
            // 48dp-high hit target; the former six-column strip was too compressed on phones.
            Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                VpnOperationToggleControl(VpnOperation.SORT_AND_COLLECT, R.string.aicp_op_sort_collect, activeOperation, busy, onAction, Modifier.weight(1f), operationProgress = operationProgress)
                VpnOperationToggleControl(VpnOperation.TEST_REAL_DELAYS, R.string.aicp_op_test_delays, activeOperation, busy, onAction, Modifier.weight(1f), operationProgress = operationProgress)
                VpnOperationToggleControl(VpnOperation.SELECTED_CONFIG_PING, R.string.aicp_op_selected_ping, activeOperation, busy, onAction, Modifier.weight(1f), operationProgress = operationProgress)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                VpnOperationToggleControl(VpnOperation.FIND_PUBLIC_CONFIGS, R.string.aicp_op_find_public, activeOperation, busy, onAction, Modifier.weight(1f), operationProgress = operationProgress)
                VpnOperationToggleControl(VpnOperation.VERIFY_EXIT, R.string.aicp_op_verify_exit, activeOperation, busy, onAction, Modifier.weight(1f), operationProgress = operationProgress)
                VpnOperationToggleControl(VpnOperation.SMART_CONNECT, R.string.aicp_op_smart_connect, activeOperation, busy, onAction, Modifier.weight(1f), isRunning, smartConnectEnabled, operationProgress)
            }
        }
        // A restrained animated halo gives the AICP connect control a clear identity without
        // adding a heavy image or distracting from the server list.
        if (isRunning) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 18.dp)
                    .offset(y = (-34).dp)
                    .size(68.dp)
                    .graphicsLayer { alpha = pulseAlpha }
                    .background(colorFabActive, CircleShape)
            )
        }
        FloatingActionButton(
            onClick = {
                if (!isRunning) {
                    scope.launch {
                        rotationAnim.animateTo(
                            targetValue = 720f,
                            animationSpec = tween(durationMillis = 3000)
                        )
                    }
                }
                onAction(MainAction.ToggleService)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp)
                .offset(y = (-28).dp)
                .navigationBarsPadding(),
            containerColor = if (isRunning) colorFabActive
            else if (isDarkTheme) colorFabInactiveDark
            else colorFabInactiveLight
        ) {
            Text(
                text = "AICP",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .graphicsLayer { rotationZ = rotationAnim.value }
                    .semantics {
                        contentDescription = connectDescription
                    }
            )
        }
    }
}


@Composable
private fun VpnOperationToggleControl(
    operation: VpnOperation,
    labelRes: Int,
    activeOperation: VpnOperation?,
    busy: Boolean,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    isRunning: Boolean = false,
    smartConnectEnabled: Boolean = false,
    operationProgress: OperationProgress? = null,
) {
    val active = if (operation == VpnOperation.SMART_CONNECT) activeOperation == operation || (smartConnectEnabled && isRunning) else activeOperation == operation
    val label = stringResource(labelRes)
    val actionLabel = stringResource(if (active) R.string.aicp_op_stop else R.string.aicp_op_play)
    val buttonProgress = operationProgress?.takeIf { it.operation == operation }
    val progressPulse by rememberInfiniteTransition(label = "operation-progress-$operation").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), repeatMode = RepeatMode.Restart),
        label = "operation-progress-phase-$operation"
    )
    val enabled = active || !busy
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        active -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        active -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    if (operation == VpnOperation.SMART_CONNECT) {
                        when {
                            activeOperation == VpnOperation.SMART_CONNECT -> onAction(MainAction.StopVpnOperation(operation))
                            smartConnectEnabled && isRunning -> onAction(MainAction.ToggleSmartConnect)
                            !isRunning -> onAction(MainAction.StartVpnOperation(operation))
                            else -> onAction(MainAction.ToggleSmartConnect)
                        }
                    } else {
                        onAction(
                            if (active) MainAction.StopVpnOperation(operation)
                            else MainAction.StartVpnOperation(operation)
                        )
                    }
                },
            )
            .semantics { contentDescription = "$actionLabel · $label" },
        shape = RoundedCornerShape(7.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 1.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = (if (active) "■ " else "▶ ") + actionLabel + (buttonProgress?.let { progress ->
                        when {
                            operation == VpnOperation.FIND_PUBLIC_CONFIGS -> "  +${progress.found} −${progress.removed} · ${progress.retained}"
                            progress.total > 0 -> "  ${progress.completed}/${progress.total}"
                            else -> ""
                        }
                    } ?: ""),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (buttonProgress != null) {
                val progressColor = when (operation) {
                    VpnOperation.FIND_PUBLIC_CONFIGS -> MaterialTheme.colorScheme.tertiary
                    VpnOperation.TEST_REAL_DELAYS, VpnOperation.SELECTED_CONFIG_PING -> MaterialTheme.colorScheme.primary
                    VpnOperation.SORT_AND_COLLECT -> MaterialTheme.colorScheme.secondary
                    VpnOperation.VERIFY_EXIT -> MaterialTheme.colorScheme.tertiary
                    VpnOperation.SMART_CONNECT -> MaterialTheme.colorScheme.primary
                }
                val progressTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)) {
                    val radius = size.height / 2f
                    drawRoundRect(progressTrackColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius))
                    if (buttonProgress.total > 0 && buttonProgress.fraction > 0f) {
                        drawRoundRect(progressColor, size = androidx.compose.ui.geometry.Size(size.width * buttonProgress.fraction, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius))
                    } else {
                        val segment = size.width * 0.28f
                        val left = (size.width - segment) * progressPulse
                        drawRoundRect(progressColor, topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(segment, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficRateGraph(
    title: String,
    upload: Double,
    download: Double,
    history: List<AicpTrafficRateSample>,
    tunnel: Boolean,
    scaleMax: Double,
) {
    val uploadColor = MaterialTheme.colorScheme.primary
    val downloadColor = MaterialTheme.colorScheme.tertiary
    val uploadValues = history.map { if (tunnel) it.tunnelUploadBytesPerSecond else it.directUploadBytesPerSecond }
    val downloadValues = history.map { if (tunnel) it.tunnelDownloadBytesPerSecond else it.directDownloadBytesPerSecond }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
        Canvas(modifier = Modifier.weight(1f).heightIn(min = 24.dp, max = 24.dp)) {
            val maxRate = scaleMax.coerceAtLeast(1.0)
            val left = 2.dp.toPx()
            val right = size.width - 2.dp.toPx()
            val top = 2.dp.toPx()
            val bottom = size.height - 2.dp.toPx()
            fun drawSeries(values: List<Double>, color: Color) {
                if (values.size < 2) return
                val points = values.mapIndexed { index, value ->
                    val x = left + (right - left) * index / (values.size - 1).toFloat()
                    val y = bottom - (bottom - top) * (value / maxRate).toFloat().coerceIn(0f, 1f)
                    androidx.compose.ui.geometry.Offset(x, y)
                }
                points.zipWithNext().forEach { (start, end) ->
                    drawLine(color, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            drawSeries(uploadValues, uploadColor)
            drawSeries(downloadValues, downloadColor)
        }
        Row(modifier = Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("↑${upload.toLong().toSpeedString()}", style = MaterialTheme.typography.labelSmall, color = uploadColor, maxLines = 1)
            Text("↓${download.toLong().toSpeedString()}", style = MaterialTheme.typography.labelSmall, color = downloadColor, maxLines = 1, modifier = Modifier.padding(start = 3.dp))
        }
    }
}

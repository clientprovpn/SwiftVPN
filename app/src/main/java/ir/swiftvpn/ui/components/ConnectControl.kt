package ir.swiftvpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.swiftvpn.R
import ir.swiftvpn.engine.VpnState
import ir.swiftvpn.ui.StatusColors

/**
 * The primary connect / disconnect control.
 *
 * Replaces the Switch that used to sit in each list row. A Switch has only two
 * visual states, so the connecting phase had nowhere to live — and because its
 * `checked` value came from the engine's confirmation (which lands seconds
 * later) a tap looked completely dead. This control renders three states:
 *
 *  - idle       filled play triangle on a muted circle
 *  - connecting spinning arc around the circle
 *  - active     stop square on the accent circle
 *
 * The ripple is clipped to the circle, so no square halo flashes on tap.
 */
@Composable
fun ConnectControl(
    vpnState: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val accent by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> StatusColors.connected
            VpnState.CONNECTING -> StatusColors.connecting
            VpnState.AUTH_FAILED, VpnState.NO_NETWORK -> StatusColors.error
            VpnState.PAUSED -> StatusColors.connecting
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(320),
        label = "connectAccent",
    )

    val spin = rememberInfiniteTransition(label = "connectSpin")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = if (vpnState == VpnState.CONNECTING) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connectRotation",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = tween(90),
        label = "connectPress",
    )

    val filled = vpnState.isActive

    Box(
        modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (vpnState == VpnState.CONNECTING) {
            Canvas(
                Modifier
                    .size(size)
                    .rotate(rotation)
            ) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Box(
            Modifier
                .size(size * 0.78f)
                .scale(pressScale)
                .clip(CircleShape)
                .background(
                    if (filled) accent
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                )
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(
                        color = if (filled) Color.White else accent,
                    ),
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (filled) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (filled) R.string.action_disconnect else R.string.action_connect
                ),
                tint = if (filled) Color.White else accent,
                modifier = Modifier.size(size * 0.44f),
            )
        }
    }
}

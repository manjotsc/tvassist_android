package com.tvassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvassist.data.ha.ConnectionState

@Composable
internal fun ConnectionStatusLine(state: ConnectionState) {
    val (label, color) = statusLabelColor(state)
    Text(text = label, color = color, fontSize = 16.sp)
}

/** Small top-left connection indicator: a colored dot + short status. */
@Composable
internal fun CompactStatus(state: ConnectionState) {
    val (label, color) = statusLabelColor(state)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, color = Color(0xFFBBBBBB), fontSize = 13.sp)
    }
}

internal fun statusLabelColor(state: ConnectionState): Pair<String, Color> = when (state) {
    ConnectionState.Connected -> "Connected" to Color(0xFF4CAF50)
    ConnectionState.Connecting -> "Connecting…" to Color(0xFFFFC107)
    ConnectionState.Authenticating -> "Authenticating…" to Color(0xFFFFC107)
    ConnectionState.Disconnected -> "Disconnected" to Color(0xFF9E9E9E)
    is ConnectionState.Failed -> "Error: ${state.reason}" to Color(0xFFF44336)
}

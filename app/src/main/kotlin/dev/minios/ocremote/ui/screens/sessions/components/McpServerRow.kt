package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.McpServerStatus

@Composable
fun McpServerRow(
    server: McpServerStatus,
    isLoading: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = buildString {
                    append(server.type)
                    append(" \u00b7 ")
                    append(statusDot(server.status))
                    append(" ")
                    append(server.status)
                },
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(server.status)
            )
        }
        Switch(
            checked = server.status == "connected",
            onCheckedChange = { onToggle() },
            enabled = !isLoading
                    && server.status != "needs_auth"
                    && server.status != "needs_client_registration"
        )
    }
}

private fun statusDot(status: String): String = when (status) {
    "connected" -> "\u25cf"
    "disabled" -> "\u25cb"
    "failed" -> "\u25cf"
    "needs_auth" -> "\u25cf"
    "needs_client_registration" -> "\u25cf"
    else -> "\u25cb"
}

private fun statusColor(status: String): Color = when (status) {
    "connected" -> Color(0xFF4CAF50)
    "disabled" -> Color.Gray
    "failed" -> Color(0xFFF44336)
    "needs_auth", "needs_client_registration" -> Color(0xFFFFC107)
    else -> Color.Gray
}

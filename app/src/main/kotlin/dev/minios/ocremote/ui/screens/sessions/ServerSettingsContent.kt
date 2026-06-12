package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.McpServerStatus
import dev.minios.ocremote.ui.screens.sessions.components.McpServerRow

@Composable
fun ServerSettingsContent(
    mcpServers: List<McpServerStatus>,
    mcpLoading: String?,
    mcpInitialLoading: Boolean,
    modifier: Modifier = Modifier,
    onToggleMcp: (name: String) -> Unit = {},
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = "MCP Servers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        when {
            mcpInitialLoading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }
            mcpServers.isEmpty() -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No MCP servers configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                items(count = mcpServers.size, key = { mcpServers[it].name }) { index ->
                    val server = mcpServers[index]
                    McpServerRow(
                        server = server,
                        isLoading = mcpLoading == server.name,
                        onToggle = { onToggleMcp(server.name) }
                    )
                }
            }
        }
    }
}

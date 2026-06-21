package dev.leonardo.ocremotev2.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Navigation route definition for the File Viewer screen.
 * Parameters: server params + sessionId, filePath, source, toolPartIds, directory
 */
object FileViewerNav {
    const val ROUTE = "file_viewer"
    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_FILE_PATH = "filePath"
    const val PARAM_SOURCE = "source"
    const val PARAM_TOOL_PART_IDS = "toolPartIds"
    const val PARAM_DIRECTORY = "directory"

    object Source {
        const val LIVE = "live"
        const val GIT_DIFF = "git_diff"
        const val TOOL_SNAPSHOT = "tool_snapshot"
        const val TOOL_SNAPSHOT_DIFF = "tool_snapshot_diff"
    }

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_FILE_PATH) { type = NavType.StringType },
        navArgument(PARAM_SOURCE) { type = NavType.StringType },
        navArgument(PARAM_TOOL_PART_IDS) { type = NavType.StringType },
        navArgument(PARAM_DIRECTORY) { type = NavType.StringType; defaultValue = "" },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_SESSION_ID={$PARAM_SESSION_ID}&$PARAM_FILE_PATH={$PARAM_FILE_PATH}&$PARAM_SOURCE={$PARAM_SOURCE}&$PARAM_TOOL_PART_IDS={$PARAM_TOOL_PART_IDS}&$PARAM_DIRECTORY={$PARAM_DIRECTORY}"

    data class Params(
        val server: ServerRouteParams,
        val sessionId: String,
        val filePath: String,
        val source: String,
        val toolPartIds: String = "",
        val directory: String = ""
    )

    fun createRoute(
        serverUrl: String,
        username: String,
        password: String,
        serverName: String,
        serverId: String,
        sessionId: String,
        filePath: String,
        source: String,
        toolPartIds: String = "",
        directory: String = ""
    ): String {
        val serverQuery = ServerRouteParams.queryString(serverUrl, username, password, serverName, serverId)
        val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
        val encodedFilePath = URLEncoder.encode(filePath, "UTF-8")
        val encodedSource = URLEncoder.encode(source, "UTF-8")
        val encodedToolPartIds = URLEncoder.encode(toolPartIds, "UTF-8")
        val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
        return "$ROUTE?$serverQuery&$PARAM_SESSION_ID=$encodedSessionId&$PARAM_FILE_PATH=$encodedFilePath&$PARAM_SOURCE=$encodedSource&$PARAM_TOOL_PART_IDS=$encodedToolPartIds&$PARAM_DIRECTORY=$encodedDirectory"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        val server = entry.serverRouteParams()
        val sessionId = URLDecoder.decode(entry.arguments?.getString(PARAM_SESSION_ID).orEmpty(), "UTF-8")
        val filePath = URLDecoder.decode(entry.arguments?.getString(PARAM_FILE_PATH).orEmpty(), "UTF-8")
        val source = URLDecoder.decode(entry.arguments?.getString(PARAM_SOURCE).orEmpty(), "UTF-8")
        val toolPartIds = URLDecoder.decode(entry.arguments?.getString(PARAM_TOOL_PART_IDS).orEmpty(), "UTF-8")
        val directory = URLDecoder.decode(entry.arguments?.getString(PARAM_DIRECTORY).orEmpty(), "UTF-8")
        return Params(server = server, sessionId = sessionId, filePath = filePath, source = source, toolPartIds = toolPartIds, directory = directory)
    }
}

package dev.minios.ocremote.ui.navigation.routes

import android.util.Log
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Navigation route definition for the Chat screen.
 * Parameters: serverUrl, username, password, serverName, serverId, sessionId, openTerminal
 */
object ChatNav {
    const val ROUTE = "chat"

    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_OPEN_TERMINAL = "openTerminal"

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_OPEN_TERMINAL) { type = NavType.BoolType; defaultValue = false },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_SESSION_ID={$PARAM_SESSION_ID}&$PARAM_OPEN_TERMINAL={$PARAM_OPEN_TERMINAL}"

    data class Params(
        val server: ServerRouteParams,
        val sessionId: String,
        val openTerminal: Boolean = false
    )

    fun createRoute(
        serverUrl: String,
        username: String,
        password: String,
        serverName: String,
        serverId: String,
        sessionId: String,
        openTerminal: Boolean = false
    ): String {
        Log.d("P0-1-DEBUG", "ChatNav.createRoute: sessionId='$sessionId', serverId='$serverId', serverUrl='$serverUrl', openTerminal=$openTerminal")
        val serverQuery = ServerRouteParams.queryString(serverUrl, username, password, serverName, serverId)
        val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
        val route = "$ROUTE?$serverQuery&$PARAM_SESSION_ID=$encodedSessionId&$PARAM_OPEN_TERMINAL=$openTerminal"
        Log.d("P0-1-DEBUG", "ChatNav.createRoute: result=$route")
        return route
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        Log.d("P0-1-DEBUG", "ChatNav.fromEntry: raw sessionId=${entry.arguments?.getString(PARAM_SESSION_ID)}")
        val server = entry.serverRouteParams()
        val sessionId = URLDecoder.decode(entry.arguments?.getString(PARAM_SESSION_ID).orEmpty(), "UTF-8")
        val openTerminal = entry.arguments?.getBoolean(PARAM_OPEN_TERMINAL) ?: false
        Log.d("P0-1-DEBUG", "ChatNav.fromEntry: parsed sessionId='$sessionId', openTerminal=$openTerminal, serverId='${server.serverId}'")
        return Params(server = server, sessionId = sessionId, openTerminal = openTerminal)
    }
}

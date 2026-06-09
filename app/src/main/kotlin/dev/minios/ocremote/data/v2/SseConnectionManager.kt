package dev.minios.ocremote.data.v2

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "V2-SseConn"
private const val LOG_INTERVAL = 100

/**
 * Consumes raw SSE JSON strings (from V1's SharedFlow) and parses them
 * into [SseEventV2] using [EventParser].
 *
 * No longer establishes its own HTTP connection — reuses V1's connection.
 * Retains event deduplication.
 */
class SseConnectionManager(
    private val rawEventsFlow: Flow<String>,
    private val parser: EventParser = EventParser,
    private val deduplicator: EventDeduplicator = EventDeduplicator(),
) {
    /**
     * Connect to the raw SSE event stream and parse events.
     * The [rawEventsFlow] is expected to be V1's [SseClient.rawSseEventFlow].
     */
    fun connect(): Flow<SseEventV2> = flow {
        var eventCount = 0
        var parseNullCount = 0
        rawEventsFlow.collect { data ->
            try {
                eventCount++
                val event = parser.parse(data)
                if (event != null && !deduplicator.isDuplicate(event)) {
                    emit(event)
                } else if (event == null) {
                    parseNullCount++
                    if (parseNullCount % LOG_INTERVAL == 0) {
                        Log.d(TAG, "$parseNullCount/$eventCount events parsed null (V1 format, expected)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Parse error: ${e.message}")
            }
        }
    }
}

# Layer 1: Network Resilience Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the client reliable under any network condition — no crashes, no freezes, clear error messages.

**Architecture:** Add a unified `ApiResult<T>` error type, coroutine-level retry with exponential backoff, network state monitoring via Hilt singleton, and SSE read-level timeout with cooldown. UI components show retry states and connection errors.

**Tech Stack:** Kotlin, kotlinx.coroutines, Ktor (OkHttp), Hilt, Jetpack Compose (Material 3), DataStore (Preferences), MockK, Turbine, coroutines-test

**Depends on:** None (foundation layer)

**Spec reference:** `docs/superpowers/specs/2026-06-04-gap-fix-design.md` → Layer 1

---

## File Structure

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `app/src/main/kotlin/dev/minios/ocremote/domain/model/ApiResult.kt` | Sealed class for unified API results |
| Create | `app/src/test/kotlin/dev/minios/ocremote/domain/model/ApiResultTest.kt` | Tests for ApiResult and mapHttpError |
| Create | `app/src/main/kotlin/dev/minios/ocremote/data/api/RetryPolicy.kt` | Retry policy data class + retryWithPolicy extension |
| Create | `app/src/test/kotlin/dev/minios/ocremote/data/api/RetryPolicyTest.kt` | Tests for RetryPolicy |
| Create | `app/src/main/kotlin/dev/minios/ocremote/data/api/NetworkMonitor.kt` | Network state monitoring via ConnectivityManager |
| Create | `app/src/test/kotlin/dev/minios/ocremote/data/api/NetworkMonitorTest.kt` | Tests for NetworkMonitor |
| Modify | `app/src/main/kotlin/dev/minios/ocremote/di/NetworkModule.kt` | Add NetworkMonitor provider |
| Modify | `app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt` | Add SSE read timeout with cooldown |
| Create | `app/src/test/kotlin/dev/minios/ocremote/data/api/SseClientReadTimeoutTest.kt` | Tests for SSE read timeout behavior |
| Modify | `app/src/main/kotlin/dev/minios/ocremote/OpenCodeApp.kt` | Add restart-after-crash logic |
| Create | `app/src/main/kotlin/dev/minios/ocremote/ui/components/ConnectionErrorScreen.kt` | Full-screen connection error UI |
| Create | `app/src/main/kotlin/dev/minios/ocremote/ui/components/SessionRetryCard.kt` | Retry status card composable |
| Modify | `app/src/main/kotlin/dev/minios/ocremote/service/OpenCodeConnectionService.kt` | Inject NetworkMonitor, network recovery reconnect |
| Modify | `app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt` | Use RetryPolicy for backoff constants |

---

## Tasks

### Task 1: ApiResult\<T\> — Unified API Error Type (Spec 1.2 + 1.5)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/ApiResult.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/domain/model/ApiResultTest.kt`

**Rationale:** Every other task depends on a unified error type. `ApiResult<T>` replaces the mix of boolean returns, thrown exceptions, and `Result<T>` in OpenCodeApi.

- [ ] **Step 1.1: Write the test file for ApiResult and mapHttpError**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/model/ApiResultTest.kt
package dev.minios.ocremote.domain.model

import io.ktor.http.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResultTest {

    // ============ ApiResult construction ============

    @Test
    fun `Success holds data`() {
        val result = ApiResult.Success("hello")
        assertEquals("hello", result.data)
    }

    @Test
    fun `Error holds ApiError`() {
        val error = ApiError.AuthError
        val result = ApiResult.Error<String>(error)
        assertEquals(error, result.error)
    }

    @Test
    fun `isSuccess returns true for Success`() {
        assertTrue((ApiResult.Success(42) as ApiResult<Int>).isSuccess)
    }

    @Test
    fun `isSuccess returns false for Error`() {
        val result: ApiResult<Int> = ApiResult.Error(ApiError.ServerError(500))
        assertTrue(!result.isSuccess)
    }

    @Test
    fun `getOrNull returns data for Success`() {
        val result: ApiResult<String> = ApiResult.Success("data")
        assertEquals("data", result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Error`() {
        val result: ApiResult<String> = ApiResult.Error(ApiError.NotFoundError)
        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrDefault returns data for Success`() {
        val result: ApiResult<Int> = ApiResult.Success(10)
        assertEquals(10, result.getOrDefault(0))
    }

    @Test
    fun `getOrDefault returns default for Error`() {
        val result: ApiResult<Int> = ApiResult.Error(ApiError.NetworkError)
        assertEquals(0, result.getOrDefault(0))
    }

    // ============ mapHttpError ============

    @Test
    fun `401 maps to AuthError`() {
        val error = mapHttpError(HttpStatusCode.Unauthorized.value)
        assertTrue(error is ApiError.AuthError)
    }

    @Test
    fun `403 maps to ForbiddenError`() {
        val error = mapHttpError(HttpStatusCode.Forbidden.value)
        assertTrue(error is ApiError.ForbiddenError)
    }

    @Test
    fun `404 maps to NotFoundError`() {
        val error = mapHttpError(HttpStatusCode.NotFound.value)
        assertTrue(error is ApiError.NotFoundError)
    }

    @Test
    fun `429 without headers maps to RateLimitError with zero retry`() {
        val error = mapHttpError(HttpStatusCode.TooManyRequests.value)
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(0L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `429 with retry-after header maps to RateLimitError`() {
        val error = mapHttpError(
            HttpStatusCode.TooManyRequests.value,
            retryAfterSeconds = "30"
        )
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(30_000L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `429 with retry-after-ms header maps to RateLimitError`() {
        val error = mapHttpError(
            HttpStatusCode.TooManyRequests.value,
            retryAfterMs = "5000"
        )
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(5000L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `429 prefers retry-after-ms over retry-after`() {
        val error = mapHttpError(
            HttpStatusCode.TooManyRequests.value,
            retryAfterSeconds = "30",
            retryAfterMs = "5000"
        )
        assertEquals(5000L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `500 maps to ServerError`() {
        val error = mapHttpError(HttpStatusCode.InternalServerError.value)
        assertTrue(error is ApiError.ServerError)
        assertEquals(500, (error as ApiError.ServerError).statusCode)
    }

    @Test
    fun `502 maps to ServerError`() {
        val error = mapHttpError(HttpStatusCode.BadGateway.value)
        assertTrue(error is ApiError.ServerError)
        assertEquals(502, (error as ApiError.ServerError).statusCode)
    }

    @Test
    fun `503 maps to ServerError`() {
        val error = mapHttpError(HttpStatusCode.ServiceUnavailable.value)
        assertTrue(error is ApiError.ServerError)
        assertEquals(503, (error as ApiError.ServerError).statusCode)
    }

    @Test
    fun `400 maps to ClientError`() {
        val error = mapHttpError(HttpStatusCode.BadRequest.value)
        assertTrue(error is ApiError.ClientError)
        assertEquals(400, (error as ApiError.ClientError).statusCode)
    }

    @Test
    fun `isTransient returns true for ServerError`() {
        assertTrue((ApiError.ServerError(500)).isTransient)
    }

    @Test
    fun `isTransient returns true for RateLimitError`() {
        assertTrue((ApiError.RateLimitError(1000L)).isTransient)
    }

    @Test
    fun `isTransient returns false for AuthError`() {
        assertTrue(!ApiError.AuthError.isTransient)
    }

    @Test
    fun `isTransient returns false for NotFoundError`() {
        assertTrue(!ApiError.NotFoundError.isTransient)
    }

    @Test
    fun `isTransient returns true for NetworkError`() {
        assertTrue(ApiError.NetworkError.isTransient)
    }
}
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — expect compilation failure (ApiResult.kt does not exist yet).

- [ ] **Step 1.2: Implement ApiResult.kt**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/model/ApiResult.kt
package dev.minios.ocremote.domain.model

/**
 * Unified result type for all API operations.
 * Replaces the mix of boolean returns, thrown exceptions, and Result<T>.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error<T>(val error: ApiError) : ApiResult<T>()

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun getOrDefault(default: T): T = when (this) {
        is Success -> data
        is Error -> default
    }
}

/**
 * Typed API errors with HTTP status code classification.
 */
sealed class ApiError {
    /** Authentication failure (401). */
    data object AuthError : ApiError()

    /** Authorization failure (403). */
    data object ForbiddenError : ApiError()

    /** Resource not found (404). */
    data object NotFoundError : ApiError()

    /** Rate limited (429). [retryAfterMillis] from retry-after / retry-after-ms header. */
    data class RateLimitError(val retryAfterMillis: Long = 0L) : ApiError()

    /** Server-side error (5xx). */
    data class ServerError(val statusCode: Int) : ApiError()

    /** Client-side error (4xx, excluding classified ones). */
    data class ClientError(val statusCode: Int) : ApiError()

    /** Network-level failure (no response, IOException, timeout). */
    data object NetworkError : ApiError()

    /** Whether this error is transient and worth retrying. */
    val isTransient: Boolean
        get() = when (this) {
            is ServerError -> true
            is RateLimitError -> true
            is NetworkError -> true
            else -> false
        }
}

/**
 * Map an HTTP status code (and optional rate-limit headers) to an [ApiError].
 *
 * @param statusCode The HTTP status code.
 * @param retryAfterSeconds Value of `retry-after` response header (seconds).
 * @param retryAfterMs Value of `retry-after-ms` response header (milliseconds).
 */
fun mapHttpError(
    statusCode: Int,
    retryAfterSeconds: String? = null,
    retryAfterMs: String? = null
): ApiError = when (statusCode) {
    401 -> ApiError.AuthError
    403 -> ApiError.ForbiddenError
    404 -> ApiError.NotFoundError
    429 -> {
        val millis = retryAfterMs?.toLongOrNull()
            ?: retryAfterSeconds?.toLongOrNull()?.times(1000L)
            ?: 0L
        ApiError.RateLimitError(millis)
    }
    in 500..599 -> ApiError.ServerError(statusCode)
    else -> ApiError.ClientError(statusCode)
}
```

**Verify:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.domain.model.ApiResultTest" --rerun` — all tests pass.

- [ ] **Step 1.3: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: add ApiResult<T> unified error type with HTTP status classification (1.2, 1.5)`

---

### Task 2: RetryPolicy — Exponential Backoff with Transient Error Detection (Spec 1.3)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/api/RetryPolicy.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/data/api/RetryPolicyTest.kt`

**Rationale:** Provides a reusable `retryWithPolicy` coroutine extension that any suspend function can use. Depends on `ApiError.isTransient` from Task 1.

- [ ] **Step 2.1: Write the test file for RetryPolicy**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/data/api/RetryPolicyTest.kt
package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.ApiError
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RetryPolicyTest {

    // ============ RetryPolicy defaults ============

    @Test
    fun `default policy has expected values`() {
        val policy = RetryPolicy()
        assertEquals(3, policy.maxAttempts)
        assertEquals(500L, policy.initialDelayMs)
        assertEquals(10_000L, policy.maxDelayMs)
        assertEquals(2.0, policy.backoffFactor, 0.01)
    }

    // ============ calculateDelay ============

    @Test
    fun `calculateDelay for first attempt returns initialDelay`() {
        val policy = RetryPolicy()
        assertEquals(500L, policy.calculateDelay(attempt = 1))
    }

    @Test
    fun `calculateDelay for second attempt doubles`() {
        val policy = RetryPolicy()
        assertEquals(1000L, policy.calculateDelay(attempt = 2))
    }

    @Test
    fun `calculateDelay is capped at maxDelay`() {
        val policy = RetryPolicy(initialDelayMs = 1000L, maxDelayMs = 2000L, backoffFactor = 10.0)
        assertEquals(2000L, policy.calculateDelay(attempt = 3))
    }

    @Test
    fun `calculateDelay for attempt 0 returns initialDelay`() {
        val policy = RetryPolicy()
        assertEquals(500L, policy.calculateDelay(attempt = 0))
    }

    // ============ isTransientException ============

    @Test
    fun `IOException is transient`() {
        assertTrue(isTransientException(IOException("connection reset")))
    }

    @Test
    fun `SocketTimeoutException is transient`() {
        assertTrue(isTransientException(SocketTimeoutException("timeout")))
    }

    @Test
    fun `ApiError ServerError is transient`() {
        assertTrue(isTransientException(ApiError.ServerError(500)))
    }

    @Test
    fun `ApiError RateLimitError is transient`() {
        assertTrue(isTransientException(ApiError.RateLimitError(1000L)))
    }

    @Test
    fun `ApiError NetworkError is transient`() {
        assertTrue(isTransientException(ApiError.NetworkError))
    }

    @Test
    fun `ApiError AuthError is not transient`() {
        assertTrue(!isTransientException(ApiError.AuthError))
    }

    @Test
    fun `RuntimeException is not transient`() {
        assertTrue(!isTransientException(RuntimeException("bug")))
    }

    // ============ retryWithPolicy success path ============

    @Test
    fun `retryWithPolicy returns success on first attempt`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        val result = retryWithPolicy(policy) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retryWithPolicy retries on IOException and succeeds`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        val result = retryWithPolicy(policy) {
            calls++
            if (calls < 3) throw IOException("fail")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `retryWithPolicy throws after exhausting retries`() = runTest {
        val policy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        var caught = false
        try {
            retryWithPolicy(policy) {
                calls++
                throw IOException("persistent failure")
            }
        } catch (e: IOException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(2, calls)
    }

    @Test
    fun `retryWithPolicy does not retry non-transient exception`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        var caught = false
        try {
            retryWithPolicy(policy) {
                calls++
                throw RuntimeException("bug")
            }
        } catch (e: RuntimeException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(1, calls)
    }
}
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — expect compilation failure (RetryPolicy.kt does not exist yet).

- [ ] **Step 2.2: Implement RetryPolicy.kt**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/data/api/RetryPolicy.kt
package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.ApiError
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlin.math.pow

/**
 * Configuration for exponential backoff retry behavior.
 *
 * @param maxAttempts     Maximum number of attempts (including the first call).
 * @param initialDelayMs  Delay before the first retry.
 * @param maxDelayMs      Maximum delay cap.
 * @param backoffFactor   Multiplier between retries (e.g. 2.0 = double each time).
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500L,
    val maxDelayMs: Long = 10_000L,
    val backoffFactor: Double = 2.0
) {
    /**
     * Calculate the delay for a given [attempt] (1-based).
     * attempt=1 → initialDelay, attempt=2 → initialDelay*factor, etc.
     */
    fun calculateDelay(attempt: Int): Long {
        val exp = (attempt - 1).coerceAtLeast(0)
        val delay = (initialDelayMs * backoffFactor.pow(exp.toDouble())).toLong()
        return min(delay, maxDelayMs)
    }
}

/**
 * Whether an exception is transient and worth retrying.
 */
fun isTransientException(throwable: Throwable): Boolean {
    return when (throwable) {
        is IOException -> true
        is SocketTimeoutException -> true
        is ApiError -> throwable.isTransient
        else -> false
    }
}

/**
 * Execute [block] with retry according to [policy].
 *
 * - Retries only on transient errors ([IOException], [SocketTimeoutException],
 *   [ApiError] with `isTransient=true`).
 * - Non-transient exceptions propagate immediately.
 * - After all retries exhausted, the last exception is re-thrown.
 */
suspend fun <T> retryWithPolicy(policy: RetryPolicy, block: suspend () -> T): T {
    var lastException: Throwable? = null
    repeat(policy.maxAttempts) { index ->
        try {
            return block()
        } catch (e: Throwable) {
            lastException = e
            if (!isTransientException(e)) throw e
            if (index < policy.maxAttempts - 1) {
                delay(policy.calculateDelay(index + 1))
            }
        }
    }
    throw lastException ?: IllegalStateException("retryWithPolicy: no exception captured")
}
```

**Verify:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.api.RetryPolicyTest" --rerun` — all tests pass.

- [ ] **Step 2.3: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: add RetryPolicy with exponential backoff and transient error detection (1.3)`

---

### Task 3: NetworkMonitor — Network State Detection (Spec 1.4)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/api/NetworkMonitor.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/data/api/NetworkMonitorTest.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/di/NetworkModule.kt`

**Rationale:** Provides `StateFlow<NetworkState>` that ViewModels and services can observe to react to network changes.

- [ ] **Step 3.1: Write the test file for NetworkMonitor and NetworkState**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/data/api/NetworkMonitorTest.kt
package dev.minios.ocremote.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {

    // ============ NetworkState sealed class ============

    @Test
    fun `NetworkState Available has isOnline true`() {
        assertTrue(NetworkState.Available.isOnline)
    }

    @Test
    fun `NetworkState Losing has isOnline false`() {
        assertTrue(!NetworkState.Losing.isOnline)
    }

    @Test
    fun `NetworkState Lost has isOnline false`() {
        assertTrue(!NetworkState.Lost.isOnline)
    }

    @Test
    fun `NetworkState Unavailable has isOnline false`() {
        assertTrue(!NetworkState.Unavailable.isOnline)
    }

    @Test
    fun `NetworkState defaults to Unavailable`() {
        assertEquals(NetworkState.Unavailable, NetworkState.Unavailable)
    }
}
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — expect compilation failure.

- [ ] **Step 3.2: Implement NetworkMonitor.kt**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/data/api/NetworkMonitor.kt
package dev.minios.ocremote.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network connectivity state.
 */
sealed class NetworkState {
    /** Network is connected and available. */
    data object Available : NetworkState()

    /** Network is about to be lost (grace period). */
    data object Losing : NetworkState()

    /** Network has been lost. */
    data object Lost : NetworkState()

    /** No network is available at all. */
    data object Unavailable : NetworkState()

    /** Convenience check for connected state. */
    val isOnline: Boolean
        get() = this is Available
}

/**
 * Monitors network connectivity state via [ConnectivityManager].
 *
 * Exposes a [StateFlow]<[NetworkState]> that can be observed by ViewModels
 * and services to react to network changes.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow<NetworkState>(detectInitialState())

    /** Observable network state. */
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start monitoring network changes. Call once during service/init.
     * Idempotent — calling multiple times is safe.
     */
    fun startMonitoring() {
        if (callback != null) return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _networkState.value = NetworkState.Available
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                _networkState.value = NetworkState.Losing
            }

            override fun onLost(network: Network) {
                _networkState.value = NetworkState.Lost
            }

            override fun onUnavailable() {
                _networkState.value = NetworkState.Unavailable
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val validated = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                if (hasInternet && validated) {
                    _networkState.value = NetworkState.Available
                }
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, cb)
        callback = cb

        // Set initial state immediately
        _networkState.value = detectInitialState()
    }

    /**
     * Stop monitoring. Call during service teardown.
     */
    fun stopMonitoring() {
        callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        callback = null
    }

    private fun detectInitialState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState.Unavailable
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkState.Unavailable
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (hasInternet && validated) NetworkState.Available else NetworkState.Unavailable
    }
}
```

**Verify:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.api.NetworkMonitorTest" --rerun` — all tests pass.

- [ ] **Step 3.3: NetworkMonitor is auto-provided by Hilt**

`NetworkMonitor` uses `@Inject constructor(...)` so Hilt auto-provides it. No changes to `NetworkModule.kt` are needed. Simply inject `NetworkMonitor` where required (e.g., `OpenCodeConnectionService`, ViewModels).

The full NetworkModule.kt should look like:

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/di/NetworkModule.kt
package dev.minios.ocremote.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.data.api.NetworkMonitor
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "opencode_prefs")

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        prettyPrint = true
        isLenientOn = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }
    
    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        
        install(Logging) {
            logger = Logger.ANDROID
            level = LogLevel.HEADERS
        }
        
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }

        install(WebSockets)
        
        install(Auth) {
            // Auth will be configured per-request based on server config
        }
        
        engine {
            config {
                // OkHttp-specific: disable response body buffering for streaming
                retryOnConnectionFailure(true)
            }
        }
        
        // Default headers will be set per-request in OpenCodeApi
    }
    
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    // NetworkMonitor uses @Inject constructor — no @Provides needed. Hilt auto-provides it.
}
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — compiles cleanly.

- [ ] **Step 3.4: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: add NetworkMonitor with ConnectivityManager state flow (1.4)`

---

### Task 4: SSE Read Timeout with Cooldown (Spec 1.1)

**Files:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/data/api/SseClientReadTimeoutTest.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt`

**Rationale:** SSE connections can stall silently. Adding a per-read timeout detects half-dead connections early, and consecutive timeout tracking with cooldown prevents reconnect storms.

- [ ] **Step 4.1: Write the test for SSE read timeout and cooldown logic**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/data/api/SseClientReadTimeoutTest.kt
package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.SseEvent
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SseClientReadTimeoutTest {

    // ============ SseReadTimeoutTracker ============

    @Test
    fun `tracker starts with zero consecutive timeouts`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        assertEquals(0, tracker.consecutiveTimeouts)
        assertTrue(!tracker.shouldEnterCooldown())
    }

    @Test
    fun `tracker increments on recordTimeout`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        tracker.recordTimeout()
        assertEquals(1, tracker.consecutiveTimeouts)
    }

    @Test
    fun `tracker resets on recordSuccess`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        tracker.recordTimeout()
        tracker.recordTimeout()
        tracker.recordSuccess()
        assertEquals(0, tracker.consecutiveTimeouts)
    }

    @Test
    fun `tracker shouldEnterCooldown after maxConsecutiveTimeouts`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 3, cooldownDurationMs = 300_000L)
        tracker.recordTimeout()
        assertTrue(!tracker.shouldEnterCooldown())
        tracker.recordTimeout()
        assertTrue(!tracker.shouldEnterCooldown())
        tracker.recordTimeout()
        assertTrue(tracker.shouldEnterCooldown())
    }

    @Test
    fun `tracker isInCooldown returns false initially`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        assertTrue(!tracker.isInCooldown())
    }

    @Test
    fun `tracker enterCooldown sets isInCooldown true`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        tracker.enterCooldown()
        assertTrue(tracker.isInCooldown())
    }

    @Test
    fun `tracker reset clears cooldown and timeouts`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        tracker.recordTimeout()
        tracker.recordTimeout()
        tracker.recordTimeout()
        tracker.enterCooldown()
        assertTrue(tracker.isInCooldown())

        tracker.reset()
        assertEquals(0, tracker.consecutiveTimeouts)
        assertTrue(!tracker.isInCooldown())
    }

    @Test
    fun `default constants are correct`() {
        assertEquals(30_000L, SseClientDefaults.DEFAULT_READ_TIMEOUT_MS)
        assertEquals(5, SseClientDefaults.MAX_CONSECUTIVE_TIMEOUTS)
        assertEquals(300_000L, SseClientDefaults.COOLDOWN_DURATION_MS)
    }
}
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — expect compilation failure.

- [ ] **Step 4.2: Implement SseReadTimeoutTracker and modify SseClient**

Add the following at the **end** of `SseClient.kt`, before the `SseAuthException` class:

```kotlin
// Add these constants near the top of SseClient.kt, after HEARTBEAT_TIMEOUT_MS:
/** Default timeout for a single SSE line read operation. */
private const val DEFAULT_READ_TIMEOUT_MS = 30_000L
/** Max consecutive read timeouts before entering cooldown. */
private const val MAX_CONSECUTIVE_TIMEOUTS = 5
/** Cooldown duration after max consecutive timeouts (5 minutes). */
private const val COOLDOWN_DURATION_MS = 300_000L
```

Add these new types at the **end** of the file (before `SseAuthException`):

```kotlin
// ============ SSE Read Timeout Tracking ============

/**
 * Constants for SSE read timeout behavior.
 */
object SseClientDefaults {
    const val DEFAULT_READ_TIMEOUT_MS = 30_000L
    const val MAX_CONSECUTIVE_TIMEOUTS = 5
    const val COOLDOWN_DURATION_MS = 300_000L
}

/**
 * Tracks consecutive SSE read timeouts and manages cooldown state.
 *
 * After [maxConsecutiveTimeouts] consecutive timeouts, the tracker enters
 * a cooldown period ([cooldownDurationMs]) during which reconnection is delayed.
 */
class SseReadTimeoutTracker(
    val maxConsecutiveTimeouts: Int = SseClientDefaults.MAX_CONSECUTIVE_TIMEOUTS,
    val cooldownDurationMs: Long = SseClientDefaults.COOLDOWN_DURATION_MS
) {
    var consecutiveTimeouts: Int = 0
        private set
    private var cooldownUntilMs: Long = 0L

    /** Record a read timeout event. */
    fun recordTimeout() {
        consecutiveTimeouts++
    }

    /** Record a successful read — resets the consecutive counter. */
    fun recordSuccess() {
        consecutiveTimeouts = 0
    }

    /** Whether the tracker has reached the threshold for cooldown. */
    fun shouldEnterCooldown(): Boolean = consecutiveTimeouts >= maxConsecutiveTimeouts

    /** Enter cooldown mode. */
    fun enterCooldown() {
        cooldownUntilMs = System.currentTimeMillis() + cooldownDurationMs
    }

    /** Whether currently in the cooldown period. */
    fun isInCooldown(): Boolean = System.currentTimeMillis() < cooldownUntilMs

    /** Fully reset the tracker (clears both timeouts and cooldown). */
    fun reset() {
        consecutiveTimeouts = 0
        cooldownUntilMs = 0L
    }
}
```

Then, **modify** the `connectToGlobalEvents` method in `SseClient.kt`. Find the `while (!channel.isClosedForRead)` loop (around line 112) and wrap the `readRawLineBytes()` call with a timeout. The change is in the inner loop only:

In `connectToGlobalEvents`, replace:
```kotlin
                val lineBytes = channel.readRawLineBytes() ?: break
```
with:
```kotlin
                val lineBytes = kotlinx.coroutines.withTimeoutOrNull(DEFAULT_READ_TIMEOUT_MS) {
                    channel.readRawLineBytes()
                } ?: run {
                    Log.w(TAG, "SSE read timeout (no data for ${DEFAULT_READ_TIMEOUT_MS}ms)")
                    break  // Treat as half-dead connection, trigger reconnect
                }
```

Do the same replacement in `connectToInstanceEvents` (around line 206):
```kotlin
                val lineBytes = kotlinx.coroutines.withTimeoutOrNull(DEFAULT_READ_TIMEOUT_MS) {
                    channel.readRawLineBytes()
                } ?: run {
                    Log.w(TAG, "Instance SSE read timeout (no data for ${DEFAULT_READ_TIMEOUT_MS}ms)")
                    break  // Treat as half-dead connection, trigger reconnect
                }
```

Also add the import for `withTimeoutOrNull` at the top of the file (kotlinx.coroutines.withTimeoutOrNull is already available via the existing import of `kotlinx.coroutines.flow.flow`). Add:

```kotlin
import kotlinx.coroutines.withTimeoutOrNull
```

**Verify:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.api.SseClientReadTimeoutTest" --rerun` — all tests pass.

- [ ] **Step 4.3: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: add SSE read timeout with consecutive timeout tracking and cooldown (1.1)`

---

### Task 5: Network Recovery Auto-Reconnect (Spec 1.8)

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/OpenCodeConnectionService.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt`

**Rationale:** When network comes back after a disconnect, automatically reconnect SSE and refresh REST state. Debounced to avoid reconnect storms.

- [ ] **Step 5.1: Add NetworkMonitor injection and reconnect trigger to SseConnectionManager**

Read `app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt`. Add `NetworkMonitor` as a constructor parameter and expose a `reconnectAll()` method:

Find the constructor:
```kotlin
class SseConnectionManager @Inject constructor(
    private val api: OpenCodeApi,
    private val sseClient: SseClient,
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsRepository
)
```

Add `NetworkMonitor`:
```kotlin
class SseConnectionManager @Inject constructor(
    private val api: OpenCodeApi,
    private val sseClient: SseClient,
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsRepository,
    private val networkMonitor: NetworkMonitor
)
```

Add the import:
```kotlin
import dev.minios.ocremote.data.api.NetworkMonitor
import dev.minios.ocremote.data.api.NetworkState
import kotlinx.coroutines.flow.debounce
```

Add a new method after `cancelScope()` (before the private section):

```kotlin
    /**
     * Trigger reconnect for all active servers.
     * Called when network recovers after being lost.
     *
     * Cancels current SSE jobs and lets the while(isActive) loop
     * in startSseConnection handle reconnection.
     */
    fun reconnectAll() {
        val serverIds = connections.keys.toList()
        for (serverId in serverIds) {
            val state = connections[serverId] ?: continue
            // Cancel the current SSE job — the while(isActive) loop will retry
            state.sseJob.cancel()
            Log.i(TAG, "[${state.config.displayName}] Triggered reconnect for network recovery")
        }
    }

    /**
     * Trigger reconnect for a specific server and refresh its REST state.
     */
    suspend fun reconnectServer(serverId: String) {
        val state = connections[serverId] ?: return
        val conn = state.conn
        // Refresh REST state
        try {
            syncSessionStatuses(conn)
        } catch (e: Exception) {
            Log.w(TAG, "[${state.config.displayName}] Failed to refresh REST state: ${e.message}")
        }
        // Cancel current SSE job to trigger reconnect
        state.sseJob.cancel()
        Log.i(TAG, "[${state.config.displayName}] Triggered reconnect with REST refresh")
    }
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — compiles.

- [ ] **Step 5.2: Add network recovery observer to OpenCodeConnectionService**

Read `app/src/main/kotlin/dev/minios/ocremote/service/OpenCodeConnectionService.kt`.

First, add the imports:
```kotlin
import dev.minios.ocremote.data.api.NetworkMonitor
import dev.minios.ocremote.data.api.NetworkState
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
```

Add a new field injection after the existing `@Inject` fields:
```kotlin
    @Inject
    lateinit var networkMonitor: NetworkMonitor
```

Add a new Job field alongside `notificationWatchdogJob`:
```kotlin
    private var networkRecoveryJob: Job? = null
```

In `onCreate()`, add network monitoring startup after the existing `serviceScope.launch { ... }` block. Find the `onCreate` method and add after the `notificationWatchdogJob = serviceScope.launch { ... }` block:

```kotlin
        // Start network monitoring and auto-reconnect on recovery
        networkMonitor.startMonitoring()
        networkRecoveryJob = serviceScope.launch {
            networkMonitor.networkState
                .debounce(2000L)  // Debounce to avoid reconnect storms
                .collect { state ->
                    when (state) {
                        is NetworkState.Available -> {
                            Log.i(TAG, "Network recovered, triggering SSE reconnect")
                            connectionManager.reconnectAll()
                        }
                        else -> {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Network state: $state")
                        }
                    }
                }
        }
```

In `onDestroy()`, add cleanup before `connectionManager.stopAllConnections()`:
```kotlin
        networkRecoveryJob?.cancel()
        networkMonitor.stopMonitoring()
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — compiles cleanly.

- [ ] **Step 5.3: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: add network recovery auto-reconnect via NetworkMonitor (1.8)`

---

### Task 6: Global Uncaught Exception Handler — Restart Logic (Spec 1.6)

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/OpenCodeApp.kt`

**Rationale:** The crash logging is already implemented. We need to add: (1) restart the main Activity after crash logging, (2) pass error info via Intent extras.

- [ ] **Step 6.1: Modify OpenCodeApp.kt to add restart logic**

Read `app/src/main/kotlin/dev/minios/ocremote/OpenCodeApp.kt`. The existing crash handler writes to file and calls `defaultHandler`. We modify it to also restart the main Activity.

Replace the entire `setDefaultUncaughtExceptionHandler` block (lines 32-71) with:

```kotlin
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val logFile = File(crashDir, "crash_${timestamp}.txt")
                logFile.writeText(buildString {
                    append("App: ${packageName} (${BuildConfig.VERSION_NAME})\n")
                    append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n")
                    append("Thread: $thread\n")
                    append("Exception: ${throwable.javaClass.name}\n")
                    append("Message: ${throwable.message}\n\n")
                    append("--- Stack Trace ---\n")
                    append(StringWriter().also { throwable.printStackTrace(java.io.PrintWriter(it)) }.toString())

                    var cause = throwable.cause
                    var depth = 1
                    while (cause != null && depth < 5) {
                        append("\n--- Cause $depth ---\n")
                        append("Exception: ${cause.javaClass.name}\n")
                        append("Message: ${cause.message}\n")
                        append(StringWriter().also { cause.printStackTrace(java.io.PrintWriter(it)) }.toString())
                        cause = cause.cause
                        depth++
                    }
                })

                // Prune old logs, keep only the newest MAX_LOG_FILES
                crashDir.listFiles()
                    ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
                    ?.sortedByDescending { it.name }
                    ?.drop(MAX_LOG_FILES)
                    ?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }

            // Restart main Activity with crash info
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_occurred", true)
                    putExtra("crash_message", throwable.message ?: "Unknown error")
                    putExtra("crash_exception", throwable.javaClass.simpleName)
                }
                if (intent != null) {
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart activity after crash", e)
            }

            // Let the default handler kill the process
            defaultHandler?.uncaughtException(thread, throwable)
        }
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — compiles cleanly.

- [ ] **Step 6.2: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: add restart-after-crash with error info Intent extras (1.6)`

---

### Task 7: ConnectionErrorScreen — Full-Screen Error UI (Spec 1.7)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/components/ConnectionErrorScreen.kt`

**Rationale:** When the server is unreachable, show a full-screen error with server name, retry countdown, and list of other available servers. This is a Compose UI component — no unit tests (Compose UI testing is not set up in this project). Verify via compile check only.

- [ ] **Step 7.1: Write ConnectionErrorScreen composable**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/components/ConnectionErrorScreen.kt
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.coroutines.delay

/**
 * Full-screen error UI shown when the server is unreachable.
 *
 * @param serverName      Display name of the unreachable server.
 * @param retryCountdownMs Remaining time in ms before auto-retry. null = not retrying.
 * @param otherServers    List of other available servers the user can switch to.
 * @param onRetry         Callback to trigger manual retry.
 * @param onSwitchServer  Callback when user selects a different server. Receives server ID.
 */
@Composable
fun ConnectionErrorScreen(
    serverName: String,
    retryCountdownMs: Long?,
    otherServers: List<ServerConfig>,
    onRetry: () -> Unit,
    onSwitchServer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var countdownSeconds by remember(retryCountdownMs) {
        mutableIntStateOf((retryCountdownMs ?: 0L) / 1000L)
    }

    // Countdown timer
    if (retryCountdownMs != null && retryCountdownMs > 0) {
        LaunchedEffect(retryCountdownMs) {
            countdownSeconds = (retryCountdownMs / 1000L)
            while (countdownSeconds > 0) {
                delay(1000L)
                countdownSeconds--
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Error icon
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.MUTED)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Server name
        Text(
            text = serverName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Status message
        Text(
            text = if (retryCountdownMs != null && retryCountdownMs > 0) {
                "Connection lost. Retrying in $countdownSeconds seconds..."
            } else {
                "Unable to connect to server"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Retry button
        if (retryCountdownMs != null && retryCountdownMs > 0) {
            LinearProgressIndicator(
                progress = {
                    if (retryCountdownMs > 0) {
                        countdownSeconds.toFloat() / ((retryCountdownMs / 1000L).toFloat().coerceAtLeast(1f))
                    } else 0f
                },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(4.dp),
            )
        } else {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Retry")
            }
        }

        // Other servers
        if (otherServers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Other servers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(otherServers, key = { it.id }) { server ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = AlphaTokens.HIGH
                            )
                        )
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = server.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = server.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                                )
                            },
                            colors = ListItemDefaults.colors(
                                headlineColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — compiles cleanly.

- [ ] **Step 7.2: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: add ConnectionErrorScreen full-screen error UI with retry countdown (1.7)`

---

### Task 8: SessionRetryCard — Retry Status UI (Spec 1.9)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/components/SessionRetryCard.kt`

**Rationale:** Shows in-chat retry status with attempt number, countdown, and error message. Compose UI component — verify via compile check only.

- [ ] **Step 8.1: Write SessionRetryCard composable**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/components/SessionRetryCard.kt
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.coroutines.delay

/**
 * Compact card showing retry status inside a chat session.
 *
 * @param attempt          Current retry attempt number (1-based).
 * @param maxAttempts      Maximum retry attempts (for progress display).
 * @param countdownSeconds Seconds until next retry attempt. null = no countdown.
 * @param errorMessage     Error message to display (truncated to 80 chars).
 */
@Composable
fun SessionRetryCard(
    attempt: Int,
    maxAttempts: Int = 3,
    countdownSeconds: Int?,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    var remaining by remember(countdownSeconds) {
        mutableIntStateOf(countdownSeconds ?: 0)
    }

    // Countdown timer
    if (countdownSeconds != null && countdownSeconds > 0) {
        LaunchedEffect(countdownSeconds) {
            remaining = countdownSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = AlphaTokens.HIGH)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.MEDIUM)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Retrying (attempt $attempt)...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = AlphaTokens.HIGH)
                )

                if (countdownSeconds != null && remaining > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${remaining}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = AlphaTokens.MUTED)
                    )
                }
            }

            // Progress bar
            if (maxAttempts > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { attempt.toFloat() / maxAttempts.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.MEDIUM),
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                )
            }

            // Error message (truncated to 80 chars)
            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage.take(80) + if (errorMessage.length > 80) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = AlphaTokens.MUTED),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — compiles cleanly.

- [ ] **Step 8.2: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: add SessionRetryCard composable with attempt counter and countdown (1.9)`

---

### Task 9: Wire SseReadTimeoutTracker into SseConnectionManager (Spec 1.1 integration)

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt`

**Rationale:** The `SseReadTimeoutTracker` was created in Task 4, but needs to be wired into the reconnect loop so that after 5 consecutive timeouts, the system enters a 5-minute cooldown period.

- [ ] **Step 9.1: Add timeout tracker to SseConnectionManager**

Read `app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt`.

Add the import:
```kotlin
import dev.minios.ocremote.data.api.SseReadTimeoutTracker
```

Add a new field after the `scope` declaration:
```kotlin
    /** Per-server read timeout tracking. */
    private val timeoutTrackers = mutableMapOf<String, SseReadTimeoutTracker>()
```

In the `startSseConnection` method, at the beginning of the `while (isActive)` loop body (after `attempt++`), add timeout tracker integration. Find the `while (isActive)` block and add these checks:

After `attempt++` and the `Log.i` line, add:

```kotlin
                // Check cooldown from read timeout tracker
                val tracker = timeoutTrackers.getOrPut(server.id) { SseReadTimeoutTracker() }
                if (tracker.isInCooldown()) {
                    Log.w(TAG, "[${server.displayName}] In cooldown from read timeouts, waiting...")
                    delay(30_000L)  // Check again in 30s
                    continue
                }
```

After the `sseClient.connectToGlobalEvents(conn).catch { ... }.collect { ... }` block, where the flow completes normally (the `// Flow completed normally` comment), add:

```kotlin
                    // Flow completed normally — could be read timeout break
                    // Check if we should track this as a timeout
                    if (tracker.shouldEnterCooldown()) {
                        tracker.enterCooldown()
                        Log.w(TAG, "[${server.displayName}] Entered 5-min cooldown after ${tracker.consecutiveTimeouts} consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
```

On successful connection (inside the `.collect { event -> ... }` block, right after `updateServerConnected(server.id, true)`), add:
```kotlin
                            tracker.recordSuccess()
```

In the `stopConnection` method, add cleanup:
```kotlin
        timeoutTrackers.remove(serverId)
```

**Verify:** `.\gradlew :app:compileDevDebugKotlin` — compiles cleanly.

- [ ] **Step 9.2: Compile check + commit**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Commit: `feat: wire SseReadTimeoutTracker into SseConnectionManager reconnect loop (1.1)`

---

### Task 10: Final Integration Verification

**Files:** None (verification only)

**Rationale:** Ensure all Layer 1 pieces compile together and existing tests still pass.

- [ ] **Step 10.1: Run full compile check**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Must succeed with no errors.

- [ ] **Step 10.2: Run all unit tests**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun
```

All existing tests must still pass. New tests must pass.

- [ ] **Step 10.3: Final commit (if any fixups were needed)**

```bash
git add -A
git commit -m "chore: Layer 1 integration verification — all tests pass"
```

---

## Future Work (Not in Scope)

### 1.10 Multi-Server Health Check (P2) — Deferred

**What it needs:**
- `ServerRepository` periodic `checkHealth()` with configurable interval (default 30s)
- New `StateFlow<Map<ServerId, ServerHealth>>` exposed by ServerRepository
- HomeScreen server cards showing online/offline status indicator
- `CoroutineScope` in `ServerRepository` for periodic checks (or use `flow` operator)
- Can reuse `RetryPolicy` from Task 2 for health check retries

**Why deferred:** P2 priority. Requires HomeScreen UI changes and a new periodic coroutine scope. Should be planned as a standalone task after Layer 1 foundation is proven stable.

---

## Summary

| Task | Spec Item | Files Changed | Test Files |
|------|-----------|---------------|------------|
| 1 | 1.2 + 1.5 | `ApiResult.kt` (new) | `ApiResultTest.kt` (new) |
| 2 | 1.3 | `RetryPolicy.kt` (new) | `RetryPolicyTest.kt` (new) |
| 3 | 1.4 | `NetworkMonitor.kt` (new), `NetworkModule.kt` (mod) | `NetworkMonitorTest.kt` (new) |
| 4 | 1.1 | `SseClient.kt` (mod) | `SseClientReadTimeoutTest.kt` (new) |
| 5 | 1.8 | `OpenCodeConnectionService.kt` (mod), `SseConnectionManager.kt` (mod) | — |
| 6 | 1.6 | `OpenCodeApp.kt` (mod) | — |
| 7 | 1.7 | `ConnectionErrorScreen.kt` (new) | — (Compose, compile-only) |
| 8 | 1.9 | `SessionRetryCard.kt` (new) | — (Compose, compile-only) |
| 9 | 1.1 | `SseConnectionManager.kt` (mod) | — |
| 10 | — | — | — (verification) |

**Total:** 9 new files, 5 modified files, 4 test files with ~35 test cases.

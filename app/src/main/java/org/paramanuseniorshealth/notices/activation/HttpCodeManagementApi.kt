package org.paramanuseniorshealth.notices.activation

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.paramanuseniorshealth.notices.analytics.Analytics
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent
import org.paramanuseniorshealth.notices.data.awaitResult
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * The network half of the `code_management` seam, and nothing more: token, request, JSON to Map,
 * and hand both to [CodeManagementResponse.parse]. Every decision about what an answer means lives
 * there, where a JVM test can reach it.
 *
 * It uses `HttpURLConnection`, as `NoticeImageStore` does, because this app ships no network
 * library on purpose. It never throws: any failure is [CodeManagementResult.Inconclusive], never
 * [CodeManagementResult.Revoked]. Nothing but the HTTP status is logged -- no token, no code, no
 * body.
 */
class HttpCodeManagementApi(
    private val baseUrl: String,
    /** Every call reports itself here. Injected, not a singleton, so a JVM test can use a fake sink. */
    private val analytics: Analytics,
    /** Null means no signed-in user. Injected so the never-throw behaviour is testable on the JVM. */
    private val tokenProvider: suspend () -> String? = ::firebaseToken,
    private val timeoutMs: Long = CALL_TIMEOUT_MS,
    /**
     * Test seam for the request itself: (route, code, token) to the parsed reply. Null means the real
     * `HttpURLConnection` call, which stays untested on the JVM. To report a status and then fail,
     * throw [ResponseReadFailed].
     */
    private val transport: ((String, String, String) -> Reply)? = null,
) : CodeManagementApi {

    /** A response as read off the wire: the status line and the body as a JSON object, if it was one. */
    class Reply(val httpStatus: Int, val body: Map<String, Any?>?)

    /** The status line was read and then the body failed; carries the status so it is still reported. */
    class ResponseReadFailed(val httpStatus: Int, cause: Throwable) : Exception(cause)

    /** What one call came to; [httpStatus] is null whenever no response was read. */
    private class Attempt(val result: CodeManagementResult, val httpStatus: Int? = null)

    override suspend fun status(code: String): CodeManagementResult = call("status", code)

    override suspend fun activate(code: String): CodeManagementResult = call("activate", code)

    /**
     * The single exit that reports. Everything that can produce a result -- the URL guard, the
     * token, the request, the timeout -- lives in [run] and returns an [Attempt], so there is no
     * way to return from a call without passing through the one [Analytics.log] below.
     *
     * Timed with the monotonic `System.nanoTime()` from entry to just before the report, so the
     * number is what the caller waited: token fetch (a forced refresh) included. A timeout reads
     * as roughly the timeout only when it fired during the token wait. Once the request is inside
     * `post` the coroutine timeout cannot interrupt the blocking socket, so a stalled call runs to
     * the socket timeouts (3s connect + 3s read; a trickling body is unbounded) and `duration_ms`
     * may well exceed [timeoutMs]. Related quirk: a response that arrives after the timeout has
     * fired is reported as `inconclusive` with no `http_status`. A caller cancelled mid-call is
     * not reported; it never got an answer to measure.
     */
    private suspend fun call(route: String, code: String): CodeManagementResult {
        val startedAt = System.nanoTime()
        val attempt = run(route, code)
        analytics.log(
            AnalyticsEvent.CodeManagementCall(
                operation = route,
                outcome = attempt.result.analyticsName(),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                httpStatus = attempt.httpStatus,
            )
        )
        return attempt.result
    }

    /**
     * The whole operation runs under [timeoutMs], matching `DispensaryRepository.refresh`. That
     * bounds the token wait and stops the caller waiting; it cannot interrupt a blocking socket
     * read (`NoticeImageStore` documents the same limit), which the socket timeouts bound instead.
     *
     * The try/catch sits inside the timeout, so the timeout's own cancellation passes through the
     * `CancellationException` rethrow to `withTimeoutOrNull`, which turns it into null.
     */
    private suspend fun run(route: String, code: String): Attempt {
        if (!CodeManagementConfig.isUsable(baseUrl)) return Attempt(CodeManagementResult.Inconclusive)
        return withTimeoutOrNull(timeoutMs) { attempt(route, code) } ?: Attempt(CodeManagementResult.Inconclusive)
    }

    private suspend fun attempt(route: String, code: String): Attempt = try {
        val token = tokenProvider()
        if (token == null) {
            Attempt(CodeManagementResult.Inconclusive)
        } else {
            val reply = withContext(Dispatchers.IO) { (transport ?: ::httpPost)(route, code, token) }
            Attempt(CodeManagementResponse.parse(reply.httpStatus, reply.body), reply.httpStatus)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseReadFailed) {
        log("code_management $route failed after HTTP ${e.httpStatus}: ${(e.cause ?: e).javaClass.simpleName}")
        Attempt(CodeManagementResult.Inconclusive, e.httpStatus)
    } catch (e: Exception) {
        log("code_management $route failed: ${e.javaClass.simpleName}")
        Attempt(CodeManagementResult.Inconclusive)
    }

    // Logging must not be able to break the never-throw guarantee (android.util.Log is absent on
    // the JVM unit-test classpath).
    private fun log(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun httpPost(route: String, code: String, token: String): Reply {
        val connection = (URL("${baseUrl.trim().trimEnd('/')}/$route").openConnection() as HttpURLConnection).apply {
            // HttpURLConnection has no single call timeout. These two sum to the 6 second budget,
            // so the worst case is connect plus read (6s) -- and a slow trickle that beats each
            // read() individually is not bounded by either. Not 6s flat.
            connectTimeout = 3_000
            readTimeout = 3_000
            instanceFollowRedirects = false
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
            val httpStatus = connection.responseCode
            runCatching { Log.i(TAG, "code_management $route -> HTTP $httpStatus") }
            // The status line has been read: whatever goes wrong from here must carry it out, so a
            // 5xx with an unreadable body is not mistaken for "never reached the server".
            try {
                val stream = if (httpStatus < 400) connection.inputStream else connection.errorStream
                val body = stream?.use { readCapped(it) }
                return Reply(httpStatus, toMap(body))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw ResponseReadFailed(httpStatus, e)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** The body as text, or null when it exceeds [MAX_BODY_BYTES]: an oversized body is not one we wrote. */
    private fun readCapped(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BODY_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** A JSON object to a Map; anything else, or anything malformed, is null. */
    private fun toMap(text: String?): Map<String, Any?>? {
        if (text.isNullOrBlank()) return null
        return try {
            fromJson(JSONObject(text)) as Map<String, Any?>
        } catch (e: Exception) {
            null
        }
    }

    private fun fromJson(value: Any?): Any? = when (value) {
        JSONObject.NULL, null -> null
        is JSONObject -> value.keys().asSequence().associateWith { fromJson(value.get(it)) }
        else -> value
    }

    companion object {
        private const val TAG = "CodeManagementApi"
        private const val MAX_BODY_BYTES = 64 * 1024
        const val CALL_TIMEOUT_MS = 6_000L
    }
}

/** The real token: a forced refresh, as the brief mandates. Null when nobody is signed in. */
private suspend fun firebaseToken(): String? =
    FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.awaitResult()?.token

package org.paramanuseniorshealth.notices.activation

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
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
    /** Null means no signed-in user. Injected so the never-throw behaviour is testable on the JVM. */
    private val tokenProvider: suspend () -> String? = ::firebaseToken,
    private val timeoutMs: Long = CALL_TIMEOUT_MS,
) : CodeManagementApi {

    override suspend fun status(code: String): CodeManagementResult = call("status", code)

    override suspend fun activate(code: String): CodeManagementResult = call("activate", code)

    /**
     * The whole operation runs under [timeoutMs], matching `DispensaryRepository.refresh`. That
     * bounds the token wait and stops the caller waiting; it cannot interrupt a blocking socket
     * read (`NoticeImageStore` documents the same limit), which the socket timeouts bound instead.
     *
     * The try/catch sits inside the timeout, so the timeout's own cancellation passes through the
     * `CancellationException` rethrow to `withTimeoutOrNull`, which turns it into null.
     */
    private suspend fun call(route: String, code: String): CodeManagementResult {
        if (!CodeManagementConfig.isUsable(baseUrl)) return CodeManagementResult.Inconclusive
        return withTimeoutOrNull(timeoutMs) { attempt(route, code) } ?: CodeManagementResult.Inconclusive
    }

    private suspend fun attempt(route: String, code: String): CodeManagementResult = try {
        val token = tokenProvider()
        if (token == null) {
            CodeManagementResult.Inconclusive
        } else {
            withContext(Dispatchers.IO) { post(route, code, token) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("code_management $route failed: ${e.javaClass.simpleName}")
        CodeManagementResult.Inconclusive
    }

    // Logging must not be able to break the never-throw guarantee (android.util.Log is absent on
    // the JVM unit-test classpath).
    private fun log(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun post(route: String, code: String, token: String): CodeManagementResult {
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
            val stream = if (httpStatus < 400) connection.inputStream else connection.errorStream
            val body = stream?.use { readCapped(it) }
            runCatching { Log.i(TAG, "code_management $route -> HTTP $httpStatus") }
            return CodeManagementResponse.parse(httpStatus, toMap(body))
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

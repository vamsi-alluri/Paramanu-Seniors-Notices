package org.paramanuseniorshealth.notices.activation

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @Suppress("unused") private val context: Context,
    private val baseUrl: String,
) : CodeManagementApi {

    override suspend fun status(code: String): CodeManagementResult = call("status", code)

    override suspend fun activate(code: String): CodeManagementResult = call("activate", code)

    private suspend fun call(route: String, code: String): CodeManagementResult {
        if (!CodeManagementConfig.isUsable(baseUrl)) return CodeManagementResult.Inconclusive
        return try {
            // No signed-in user is Inconclusive, not an error: the caller simply cannot ask.
            val user = FirebaseAuth.getInstance().currentUser ?: return CodeManagementResult.Inconclusive
            val token = user.getIdToken(true).awaitResult().token
                ?: return CodeManagementResult.Inconclusive
            withContext(Dispatchers.IO) { post(route, code, token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "code_management $route failed: ${e.javaClass.simpleName}")
            CodeManagementResult.Inconclusive
        }
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
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            Log.i(TAG, "code_management $route -> HTTP $httpStatus")
            return CodeManagementResponse.parse(httpStatus, toMap(body))
        } finally {
            connection.disconnect()
        }
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

    private companion object {
        const val TAG = "CodeManagementApi"
    }
}

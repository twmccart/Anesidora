package com.anesidora

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PandoraException(message: String, val code: Int? = null) : Exception(message)

/**
 * Talks to Pandora's unofficial JSON API, documented at https://6xq.net/pandora-apidoc/json/
 * This is a port of sendRequest / partnerLogin / userLogin from common/js/anesidora.js.
 */
class PandoraClient(private val credentials: () -> Pair<String, String>?) {

    private class PartnerInfo(
        val partnerId: String,
        val partnerAuthToken: String,
        val syncTime: Long,
        val clientStartTime: Long,
    )

    private class UserInfo(
        val userId: String,
        val userAuthToken: String,
    )

    private var partnerInfo: PartnerInfo? = null
    private var userInfo: UserInfo? = null
    private val loginMutex = Mutex()

    val isLoggedIn: Boolean get() = userInfo != null

    fun logout() {
        userInfo = null
    }

    /** Logs in with the given credentials; throws [PandoraException] with a user-facing message. */
    suspend fun login(email: String, password: String) {
        loginMutex.withLock { loginWhileLocked(email, password) }
    }

    private suspend fun loginWhileLocked(email: String, password: String) {
        userInfo = null
        partnerLogin()
        val parameters = mapOf(
            "method" to "auth.userLogin",
            "auth_token" to requirePartner().partnerAuthToken,
            "partner_id" to requirePartner().partnerId,
        )
        val body = JSONObject()
            .put("loginType", "user")
            .put("username", email)
            .put("password", password)
            .put("partnerAuthToken", requirePartner().partnerAuthToken)
            .put("syncTime", currentSyncTime())
        val result = try {
            post(parameters, body, encrypted = true)
        } catch (exception: PandoraException) {
            if (exception.code == INVALID_LOGIN) {
                throw PandoraException("Username or password is incorrect.", exception.code)
            }
            throw exception
        }
        userInfo = UserInfo(
            userId = result.getString("userId"),
            userAuthToken = result.getString("userAuthToken"),
        )
    }

    /**
     * Calls an authenticated API method. Retries network failures with backoff, and logs in
     * again once if the user's auth token has expired (they last somewhere between 4 and 18 hours).
     */
    suspend fun call(method: String, body: JSONObject = JSONObject()): JSONObject {
        var reloggedIn = false
        var attempt = 0
        while (true) {
            ensureLoggedIn()
            val user = userInfo ?: throw PandoraException("Not logged in.")
            val partner = requirePartner()
            val parameters = mapOf(
                "method" to method,
                "auth_token" to user.userAuthToken,
                "partner_id" to partner.partnerId,
                "user_id" to user.userId,
            )
            body.put("userAuthToken", user.userAuthToken)
            body.put("syncTime", currentSyncTime())
            try {
                return post(parameters, body, encrypted = true)
            } catch (exception: PandoraException) {
                when {
                    exception.code == INVALID_AUTH_TOKEN && !reloggedIn -> {
                        reloggedIn = true
                        userInfo = null
                    }
                    // Code 0 is "internal error", which is usually rate limiting. Don't hammer.
                    exception.code == INTERNAL_ERROR -> throw exception
                    attempt < MAX_RETRIES -> {
                        delay(((1L shl attempt) - 1) * 1000 + 1000)
                        attempt++
                    }
                    else -> throw exception
                }
            }
        }
    }

    private suspend fun ensureLoggedIn() {
        loginMutex.withLock {
            if (userInfo != null) {
                return
            }
            val (email, password) = credentials() ?: throw PandoraException("Not logged in.")
            loginWhileLocked(email, password)
        }
    }

    private suspend fun partnerLogin() {
        // Not a user login: this is Pandora's attempt to make sure only its own apps use the API.
        // We present ourselves as their (old) Android app, exactly as the extension does.
        val body = JSONObject()
            .put("username", "android")
            .put("password", "AC7IBG09A3DTSYM4R41UJWL07VLN8JI7")
            .put("version", "5")
            .put("deviceModel", "android-generic")
            .put("includeUrls", true)
        val result = post(mapOf("method" to "auth.partnerLogin"), body, encrypted = false)

        // syncTime arrives encrypted, with 4 bytes of garbage in front of the ASCII digits.
        val decryptedSyncTime = PandoraCrypto.decrypt(result.getString("syncTime"))
        val syncTime = String(decryptedSyncTime.copyOfRange(4, decryptedSyncTime.size), Charsets.US_ASCII)
            .filter { it.isDigit() }
            .toLong()

        partnerInfo = PartnerInfo(
            partnerId = result.getString("partnerId"),
            partnerAuthToken = result.getString("partnerAuthToken"),
            syncTime = syncTime,
            clientStartTime = nowInSeconds(),
        )
    }

    private fun requirePartner(): PartnerInfo =
        partnerInfo ?: throw PandoraException("Partner login has not completed.")

    private fun currentSyncTime(): Long {
        val partner = requirePartner()
        return partner.syncTime + (nowInSeconds() - partner.clientStartTime)
    }

    private suspend fun post(
        parameters: Map<String, String>,
        body: JSONObject,
        encrypted: Boolean,
    ): JSONObject = withContext(Dispatchers.IO) {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val payload = if (encrypted) PandoraCrypto.encrypt(body.toString()) else body.toString()

        val responseText = try {
            val connection = URL("$API_URL?$query").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Content-Type", if (encrypted) "text/plain" else "application/json")
                // Pandora rejects some clients by User-Agent; the extension sends this one too.
                connection.setRequestProperty("User-Agent", "libcurl")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                connection.disconnect()
            }
        } catch (exception: IOException) {
            throw PandoraException("Network error. Can you connect to pandora.com?")
        }

        val response = try {
            JSONObject(responseText)
        } catch (exception: Exception) {
            throw PandoraException("Pandora sent an unreadable response.")
        }
        if (response.optString("stat") == "fail") {
            val code = response.optInt("code", -1)
            val message = response.optString("message", "Request failed")
            throw PandoraException("$message (code $code)", code)
        }
        response.optJSONObject("result") ?: JSONObject()
    }

    private fun nowInSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        private const val API_URL = "https://tuner.pandora.com/services/json/"
        private const val MAX_RETRIES = 2
        private const val INTERNAL_ERROR = 0
        private const val INVALID_AUTH_TOKEN = 1001
        private const val INVALID_LOGIN = 1002
    }
}

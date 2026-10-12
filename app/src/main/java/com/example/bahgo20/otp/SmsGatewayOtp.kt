package com.example.bahgo20.otp   // adjust to your package

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Sends OTPs through the android-sms-gateway project (capcom6/android-sms-gateway).
 * This is NOT a library you embed — it's a separate Android app you install on a
 * second phone with a SIM card. That phone becomes your SMS server and sends the
 * real text. Your app just calls its REST API.
 *
 * Cloud Server mode (recommended for a demo — works over any internet connection,
 * not just shared WiFi): POST https://api.sms-gate.app/3rdparty/v1/message
 * Local Server mode (same WiFi only): POST http://<gateway_phone_local_ip>:8080/message
 *
 * Both use HTTP Basic Auth with the username/password shown in the gateway app.
 *
 * Gradle: implementation("com.squareup.okhttp3:okhttp:4.12.0")
 * Manifest: <uses-permission android:name="android.permission.INTERNET" />
 *
 * SECURITY: the gateway username/password inside an APK can be extracted, same as
 * any API key. Fine for a capstone prototype; for real deployment, move sending to
 * your own backend. Keep credentials in local.properties -> BuildConfig, never
 * hard-coded, never committed to GitHub.
 *
 * DEV MODE: while you're still setting up the gateway phone, set DEV_MODE = true.
 * sendOtp() then skips the network call and always "sends" DEV_CODE ("000000").
 */
class SmsGatewayOtp(
    private val username: String = "MKY8MA",   // e.g. BuildConfig.SMSGATEWAY_USERNAME
    private val password: String = "cfji94kz4ujuos",   // e.g. BuildConfig.SMSGATEWAY_PASSWORD
    private val baseUrl: String = "https://api.sms-gate.app/3rdparty/v1/message" // cloud mode
    // For local mode instead, pass e.g. "http://192.168.1.23:8080/message"
) {
    private val client = OkHttpClient()
    private val random = SecureRandom()

    private data class Pending(val code: String, val expiresAt: Long, var attempts: Int = 0)

    private val pending = mutableMapOf<String, Pending>()
    private val lastSent = mutableMapOf<String, Long>()

    companion object {
        private const val EXPIRY_MS = 5 * 60 * 1000L
        private const val RESEND_COOLDOWN_MS = 60 * 1000L
        private const val MAX_ATTEMPTS = 3

        // --- TEMPORARY: remove once the gateway phone is set up and tested ---
        private const val DEV_MODE = false
        private const val DEV_CODE = "000000"
    }

    /** 09171234567 or 9171234567 -> +639171234567 */
    fun toE164(input: String): String {
        val digits = input.filter { it.isDigit() }
        return when {
            digits.startsWith("63") -> "+$digits"
            digits.startsWith("0") -> "+63${digits.drop(1)}"
            else -> "+63$digits"
        }
    }

    suspend fun sendOtp(phone: String): Result<Unit> = withContext(Dispatchers.IO) {
        val number = toE164(phone)
        val now = System.currentTimeMillis()

        val last = lastSent[number]
        if (last != null && now - last < RESEND_COOLDOWN_MS) {
            val wait = (RESEND_COOLDOWN_MS - (now - last)) / 1000
            return@withContext Result.failure(Exception("Please wait $wait seconds before resending."))
        }

        if (DEV_MODE) {
            pending[number] = Pending(DEV_CODE, now + EXPIRY_MS)
            lastSent[number] = now
            return@withContext Result.success(Unit)
        }

        val code = (100000 + random.nextInt(900000)).toString()

        // android-sms-gateway body shape: { "textMessage": {"text": "..."}, "phoneNumbers": [...] }
        val json = JSONObject().apply {
            put("textMessage", JSONObject().put(
                "text", "Your Bahgo verification code is $code. It expires in 5 minutes. Do not share it."
            ))
            put("phoneNumbers", JSONArray().put(number))
        }

        val request = Request.Builder()
            .url(baseUrl)
            .header("Authorization", Credentials.basic(username, password))
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    pending[number] = Pending(code, now + EXPIRY_MS)
                    lastSent[number] = now
                    Result.success(Unit)
                } else {
                    val errorBody = response.body?.string() ?: "(no response body)"
                    android.util.Log.e("SmsGatewayOtp", "SMS failed (HTTP ${response.code}): $errorBody")
                    Result.failure(Exception("SMS failed (HTTP ${response.code}): $errorBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun verifyOtp(phone: String, entered: String): Result<Unit> {
        val number = toE164(phone)
        val p = pending[number] ?: return Result.failure(Exception("No code was sent to this number."))

        if (System.currentTimeMillis() > p.expiresAt) {
            pending.remove(number)
            return Result.failure(Exception("Code expired. Request a new one."))
        }
        if (p.attempts >= MAX_ATTEMPTS) {
            pending.remove(number)
            return Result.failure(Exception("Too many wrong attempts. Request a new code."))
        }
        if (entered.trim() != p.code) {
            p.attempts++
            return Result.failure(Exception("Incorrect code."))
        }

        pending.remove(number)
        return Result.success(Unit)
    }
}
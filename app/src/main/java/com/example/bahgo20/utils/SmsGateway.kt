package com.example.bahgo20.utils

import android.util.Log
import com.example.bahgo20.BuildConfig
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends a text message through SMS Gateway for Android (cloud mode, sms-gate.app).
 * Used for sign-up codes (auth/AuthApi) and Critical flood alerts (alerts/SmsAlert).
 * The gateway login comes from local.properties (SMSGATEWAY_USERNAME / _PASSWORD).
 */
object SmsGateway {
    private const val TAG = "SmsGateway"
    private const val URL = "https://api.sms-gate.app/3rdparty/v1/message"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = BuildConfig.SMSGATEWAY_USERNAME.isNotEmpty()

    /** HTTP problem from the gateway, e.g. wrong login (401). */
    class GatewayException(val code: Int) : IOException("SMS gateway answered HTTP $code")

    /** Sends [text] to one number (+63…). Blocking: call off the main thread. */
    fun send(e164: String, text: String) {
        check(isConfigured) { "SMS isn't set up in this build" }
        val body = JSONObject()
            .put("textMessage", JSONObject().put("text", text))
            .put("phoneNumbers", JSONArray().put(e164))
        val request = Request.Builder()
            .url(URL)
            .header("Authorization", Credentials.basic(BuildConfig.SMSGATEWAY_USERNAME, BuildConfig.SMSGATEWAY_PASSWORD))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "SMS failed (HTTP ${resp.code}): ${resp.body?.string()}")
                throw GatewayException(resp.code)
            }
        }
    }
}

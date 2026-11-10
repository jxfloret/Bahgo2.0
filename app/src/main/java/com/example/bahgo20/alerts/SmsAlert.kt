package com.example.bahgo20.alerts

import android.content.Context
import android.util.Log
import com.example.bahgo20.FloodAlerts
import com.example.bahgo20.SessionManager
import com.example.bahgo20.utils.EmergencyContact
import com.example.bahgo20.utils.Phone
import com.example.bahgo20.utils.SmsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Critical flood alert by SMS, sent through the SMS Gateway to the number of the account that is
 * logged in on this phone (registered users only; nobody else is texted). Each phone texts only
 * its own owner, so nobody gets the same text twice from different phones.
 *
 * Sent by FloodMonitorService when a street rises into Critical (the same moment as the
 * notification), at most once per street every [MIN_GAP_MS], to keep the texts (and their cost) low.
 */
object SmsAlert {
    private const val TAG = "SmsAlert"
    private const val PREFS = "bahgo_sms_alerts"

    /** At most one Critical text per street in this time, even if the water goes up and down. */
    const val MIN_GAP_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The text people receive. Plain ASCII and short, so it fits in one or two SMS parts. */
    fun message(street: String, levelCm: Double?): String {
        val level = levelCm?.let { String.format(Locale.US, "%.0f cm", it) } ?: "over 60 cm"
        return "BahGo ALERT: $street is CRITICAL ($level, waist-deep). Do not walk or drive through. " +
                "Emergency: ${EmergencyContact.NAME} ${EmergencyContact.NUMBER_DISPLAY}."
    }

    /** True if a text for this street is allowed now (last one was long enough ago). */
    fun allowed(lastSentMs: Long, nowMs: Long): Boolean = nowMs - lastSentMs >= MIN_GAP_MS

    /** Texts the logged-in user that [sensorId]'s street is Critical. Does nothing if nobody is logged in. */
    fun sendCritical(context: Context, sensorId: Int, levelCm: Double?) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!allowed(prefs.getLong("sent$sensorId", 0L), now)) return
        if (!SmsGateway.isConfigured) {
            Log.w(TAG, "SMS gateway not set up in this build; Critical text skipped")
            return
        }
        val session = SessionManager.getInstance(app)
        if (!session.isLoggedIn()) return

        // Reserve the slot first, so two quick readings can't send two texts
        prefs.edit().putLong("sent$sensorId", now).apply()
        scope.launch {
            val phone = session.getPhoneNumber()?.takeIf { it.isNotBlank() } ?: return@launch
            val street = FloodAlerts.STREETS[sensorId] ?: "Sensor $sensorId"
            try {
                SmsGateway.send(Phone.toE164(phone), message(street, levelCm))
                Log.i(TAG, "Critical SMS sent for $street")
            } catch (e: Exception) {
                Log.e(TAG, "Critical SMS for $street failed", e)
                prefs.edit().remove("sent$sensorId").apply()   // allow a retry on the next reading
            }
        }
    }
}

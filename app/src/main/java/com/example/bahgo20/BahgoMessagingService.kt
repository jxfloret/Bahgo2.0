package com.example.bahgo20

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives flood alert pushes, if a server sends them (see functions/index.js).
 * The app's own always-on monitor (FloodMonitorService) shows the same alerts without a server;
 * both use FloodAlerts.showAlert and the same notification id per street, so they never double up.
 */
class BahGoMessagingService : FirebaseMessagingService() {

    /**
     * The server sends data-only messages, so this runs whether the app is
     * open, in the background, or closed (but not force-stopped).
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return
        val risk = when (data["risk"]?.uppercase()) {
            "CRITICAL" -> Risk.CRITICAL
            "MEDIUM" -> Risk.MEDIUM
            else -> return
        }
        val sensorId = data["sensorId"]?.toIntOrNull() ?: 1
        FloodAlerts.showAlert(this, sensorId, risk, data["level"]?.toDoubleOrNull())
    }

    override fun onNewToken(token: String) {
        // Topic subscriptions survive token refreshes, so there's nothing to do here.
    }
}

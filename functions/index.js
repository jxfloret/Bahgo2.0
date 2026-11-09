/**
 * BahGo flood alerts (Firebase Cloud Functions, 2nd gen).
 *
 * Watches the live sensor nodes /Sensordata1 .. /Sensordata4. When a street's water level rises
 * into Medium (> 30 cm) or Critical (> 60 cm), it sends a push message to the topic
 * "flood_alerts". The app (BahGoMessagingService) turns it into a notification.
 *
 *   - Only rises alert. Going down is remembered quietly, so the next rise alerts again.
 *   - Water wobbling around 30 / 60 cm doesn't spam: it must drop 3 cm below a line to count as down,
 *     and a street gets at most one Medium alert per 10 minutes (Critical always goes out).
 *   - The last alert per street is kept in /alertState/SensordataN (its own node; the sensor
 *     nodes and /RecordedData are never written).
 *
 * Deploy:  firebase deploy --only functions:bahgo-alerts
 */
"use strict";

const { onValueWritten } = require("firebase-functions/v2/database");
const { setGlobalOptions, logger } = require("firebase-functions/v2");
const admin = require("firebase-admin");
const { levelOf, decide, alertMessage } = require("./alertLogic");

admin.initializeApp();

// The database lives in asia-southeast1; run the functions next to it.
setGlobalOptions({ region: "asia-southeast1", maxInstances: 5 });

const DB_INSTANCE = "bahgo-ee71b-default-rtdb";
const TOPIC = "flood_alerts";

async function handleReading(sensorId, event) {
  const node = event.data.after.val();
  const level = levelOf(node);
  if (level === null) return;   // node deleted, or no usable reading

  // Decide inside a transaction, so two quick writes can't both send the same alert
  let decision = null;
  const stateRef = admin.database().ref(`/alertState/Sensordata${sensorId}`);
  const result = await stateRef.transaction((state) => {
    // May run more than once (first on a cached, possibly empty value), so never abort here:
    // always return a value and let Firebase retry with the real saved state.
    decision = decide(level, state, Date.now());
    return decision.newState || state || { risk: "SAFE", sentAt: 0 };
  });

  if (!result.committed || !decision || !decision.send) return;

  const data = alertMessage(sensorId, decision.risk, level);
  try {
    await admin.messaging().send({
      topic: TOPIC,
      data,                              // data-only, so the app shows it even when closed
      android: { priority: "high" },
    });
    logger.info(`Alert sent: ${data.street} ${data.risk} at ${data.level} cm`);
  } catch (err) {
    logger.error(`Alert for Sensordata${sensorId} failed`, err);
  }
}

// One trigger per live sensor (not the whole database, so /RecordedData writes never wake it)
for (const id of [1, 2, 3, 4]) {
  exports[`floodAlertSensor${id}`] = onValueWritten(
    { ref: `/Sensordata${id}`, instance: DB_INSTANCE },
    (event) => handleReading(id, event)
  );
}

// Sign-in, sign-up, SMS codes and account changes (see auth.js). Defined after setGlobalOptions,
// so they also run in asia-southeast1.
Object.assign(exports, require("./auth"));

/**
 * Flood alert rules (plain JavaScript, no Firebase), so they can be tested with `npm test`.
 *
 * Keep these numbers identical to the app (SensorTracker.kt):
 *   SENSOR_HEIGHT_CM = 94   (37-inch device; Distance_cm on a dry street)
 *   MEDIUM_CM        = 30   (above this: Medium / Warning, knee level)
 *   CRITICAL_CM      = 60   (above this: Critical / Dangerous, waist level and up)
 */
"use strict";

const SENSOR_HEIGHT_CM = 94;
const MEDIUM_CM = 30;
const CRITICAL_CM = 60;

// The level must drop this far below a line before the risk counts as "down" again.
// Stops repeat alerts when the water wobbles around 30 or 60 cm.
const HYSTERESIS_CM = 3;

// Don't send another alert for the same street within this time, unless it got worse.
const COOLDOWN_MS = 10 * 60 * 1000;

const RANK = { SAFE: 0, MEDIUM: 1, CRITICAL: 2 };

const STREETS = {
  1: "Sealion Street",
  2: "Samaritan Street",
  3: "Swingfire Street",
  4: "Striker Street",
};

/**
 * Water level in cm from a /SensordataN node, or null if there's no usable reading.
 * Distance_cm (preferred): level = 94 - Distance_cm, clamped at 0; Distance_cm <= 0 is a bad echo.
 * Older nodes may have waterLevel / level directly.
 */
function levelOf(node) {
  if (!node || typeof node !== "object") return null;
  if (node.Distance_cm !== undefined && node.Distance_cm !== null) {
    const d = Number(node.Distance_cm);
    if (!Number.isFinite(d) || d <= 0) return null;
    return Math.max(0, SENSOR_HEIGHT_CM - d);
  }
  for (const key of ["waterLevel", "level"]) {
    if (node[key] !== undefined && node[key] !== null) {
      const v = Number(node[key]);
      return Number.isFinite(v) && v >= 0 ? v : null;
    }
  }
  return null;
}

/** Risk band for a level, with no memory (same bands as the app). */
function riskOf(level) {
  if (level > CRITICAL_CM) return "CRITICAL";
  if (level > MEDIUM_CM) return "MEDIUM";
  return "SAFE";
}

/**
 * Risk band, remembering the last one: to fall out of a band the level must drop
 * HYSTERESIS_CM below its line (going up uses the normal lines).
 */
function riskWithMemory(level, lastRisk) {
  const plain = riskOf(level);
  if (RANK[plain] >= RANK[lastRisk || "SAFE"]) return plain;
  if (lastRisk === "CRITICAL" && level > CRITICAL_CM - HYSTERESIS_CM) return "CRITICAL";
  if ((lastRisk === "CRITICAL" || lastRisk === "MEDIUM") && level > MEDIUM_CM - HYSTERESIS_CM) return "MEDIUM";
  return plain;
}

/**
 * Decides what to do with a new reading.
 *   level:  water level now (cm), or null
 *   state:  what was saved last time for this street: { risk, sentAt } (or null)
 *   nowMs:  current time
 * Returns { send: boolean, risk: string, newState: object|null }
 *   send     = true  -> push a "risk" alert for this street now
 *   newState = what to save (null = nothing changed)
 */
function decide(level, state, nowMs) {
  const last = (state && state.risk) || "SAFE";
  const sentAt = (state && state.sentAt) || 0;
  if (level === null || level === undefined) return { send: false, risk: last, newState: null };

  const risk = riskWithMemory(level, last);
  if (risk === last) return { send: false, risk, newState: null };

  if (RANK[risk] < RANK[last]) {
    // Water went down: remember it quietly, so a new rise alerts again.
    return { send: false, risk, newState: { risk, sentAt } };
  }

  // Water went up into Medium or Critical
  const escalatedToCritical = risk === "CRITICAL";
  const coolingDown = nowMs - sentAt < COOLDOWN_MS;
  if (coolingDown && !escalatedToCritical) {
    return { send: false, risk, newState: { risk, sentAt } };
  }
  return { send: true, risk, newState: { risk, sentAt: nowMs } };
}

/** The data message the app's BahGoMessagingService expects (all values are strings). */
function alertMessage(sensorId, risk, level) {
  return {
    risk,
    sensorId: String(sensorId),
    street: STREETS[sensorId] || `Sensor ${sensorId}`,
    level: level.toFixed(1),
  };
}

module.exports = {
  SENSOR_HEIGHT_CM,
  MEDIUM_CM,
  CRITICAL_CM,
  HYSTERESIS_CM,
  COOLDOWN_MS,
  STREETS,
  levelOf,
  riskOf,
  riskWithMemory,
  decide,
  alertMessage,
};

"use strict";

const test = require("node:test");
const assert = require("node:assert");
const L = require("../alertLogic");

const MIN = 60 * 1000;

test("water level from Distance_cm, same as the app", () => {
  assert.strictEqual(L.levelOf({ Distance_cm: 64 }), 30);
  assert.strictEqual(L.levelOf({ Distance_cm: 188.7 }), 0);      // dry street, clamped at 0
  assert.strictEqual(L.levelOf({ Distance_cm: 0 }), null);       // bad echo
  assert.strictEqual(L.levelOf({ Distance_cm: "not a number" }), null);
  assert.strictEqual(L.levelOf({ waterLevel: 42 }), 42);
  assert.strictEqual(L.levelOf(null), null);
});

test("risk bands: above 30 Medium, above 60 Critical", () => {
  assert.strictEqual(L.riskOf(30), "SAFE");
  assert.strictEqual(L.riskOf(30.1), "MEDIUM");
  assert.strictEqual(L.riskOf(60), "MEDIUM");
  assert.strictEqual(L.riskOf(60.1), "CRITICAL");
});

test("rising into Medium and then Critical sends two alerts", () => {
  let state = null;
  const t0 = 1_000_000_000_000;
  const a = L.decide(35, state, t0);
  assert.strictEqual(a.send, true);
  assert.strictEqual(a.risk, "MEDIUM");
  state = a.newState;

  const b = L.decide(45, state, t0 + MIN);          // still Medium: nothing new
  assert.strictEqual(b.send, false);
  assert.strictEqual(b.newState, null);

  const c = L.decide(62, state, t0 + 2 * MIN);      // Critical goes out even inside the cooldown
  assert.strictEqual(c.send, true);
  assert.strictEqual(c.risk, "CRITICAL");
});

test("wobbling around the 30 cm line does not spam", () => {
  const t0 = 1_000_000_000_000;
  let state = L.decide(31, null, t0).newState;      // alert 1
  for (const [i, level] of [29.5, 30.5, 28, 31, 29].entries()) {
    const d = L.decide(level, state, t0 + (i + 1) * MIN);
    assert.strictEqual(d.send, false, `no alert at ${level} cm`);
    if (d.newState) state = d.newState;
  }
  assert.strictEqual(state.risk, "MEDIUM");          // never dropped 3 cm below the line
});

test("after the water goes down, a new rise alerts again (after the cooldown)", () => {
  const t0 = 1_000_000_000_000;
  let state = L.decide(35, null, t0).newState;      // Medium alert
  const down = L.decide(20, state, t0 + 30 * MIN);   // well below 27 cm: back to Safe, quietly
  assert.strictEqual(down.send, false);
  assert.strictEqual(down.risk, "SAFE");
  state = down.newState;

  const soon = L.decide(35, state, t0 + 31 * MIN);   // rise again later: alert
  assert.strictEqual(soon.send, true);
});

test("a quick second Medium within 10 minutes is held back", () => {
  const t0 = 1_000_000_000_000;
  let state = L.decide(35, null, t0).newState;
  state = L.decide(20, state, t0 + 2 * MIN).newState;
  const again = L.decide(35, state, t0 + 4 * MIN);
  assert.strictEqual(again.send, false);
  assert.strictEqual(again.newState.risk, "MEDIUM"); // remembered, so it won't fire later for the same rise
});

test("no reading changes nothing", () => {
  const d = L.decide(null, { risk: "MEDIUM", sentAt: 5 }, 10);
  assert.strictEqual(d.send, false);
  assert.strictEqual(d.newState, null);
});

test("message has the fields the app reads, as strings", () => {
  assert.deepStrictEqual(L.alertMessage(3, "CRITICAL", 65.04), {
    risk: "CRITICAL",
    sensorId: "3",
    street: "Swingfire Street",
    level: "65.0",
  });
});

test("replaying Swingfire on Oct 26 (recorded data): Medium, then Critical, nothing else", () => {
  // Real levels every 5 minutes from 04:30 to 09:45 (from /RecordedData/Sensordata3)
  const levels = [
    12.5, 13.6, 14.4, 15.6, 16.5, 18.2, 19.4, 21.4, 24.0, 25.3, 27.7, 30.0, 31.6,
    34.4, 37.0, 39.8, 42.4, 43.8, 46.3, 48.2, 50.4, 52.0, 53.9, 55.8, 58.0, 59.8,
    61.7, 62.8, 64.5, 65.4, 65.9, 66.4, 66.6, 65.9, 65.5, 64.9, 63.7, 63.5, 61.9,
    61.4, 60.5, 59.6, 58.7, 57.1, 55.6, 54.0, 52.3, 50.6, 49.7, 47.9, 45.6, 44.4,
    42.1, 40.1, 39.0, 37.4, 36.1, 34.5, 33.2, 32.4, 30.4, 29.7, 28.5, 26.8];
  const t0 = 1_800_000_000_000;
  let state = null;
  const sent = [];
  levels.forEach((level, i) => {
    const d = L.decide(level, state, t0 + i * 5 * MIN);
    if (d.send) sent.push(`${d.risk}@${level}`);
    if (d.newState) state = d.newState;
  });
  assert.deepStrictEqual(sent, ["MEDIUM@31.6", "CRITICAL@61.7"]);
  assert.strictEqual(state.risk, "SAFE");            // back to Safe at the end, ready for the next flood
});

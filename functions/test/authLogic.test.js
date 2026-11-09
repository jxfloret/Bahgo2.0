"use strict";

const test = require("node:test");
const assert = require("node:assert");
const L = require("../authLogic");

const MIN = 60 * 1000;
const SALT = "AAECAwQFBgcICQoLDA0ODw==";

test("phone numbers are normalised to +63", () => {
  assert.strictEqual(L.toE164("09171234567"), "+639171234567");
  assert.strictEqual(L.toE164("9171234567"), "+639171234567");
  assert.strictEqual(L.toE164("639171234567"), "+639171234567");
  assert.strictEqual(L.toE164("+63 917 123 4567"), "+639171234567");
  assert.strictEqual(L.toE164("12345"), null);
  assert.strictEqual(L.toE164("0281234567"), null);        // landline, not mobile
  assert.strictEqual(L.keyOf("+639171234567"), "639171234567");
});

test("password rules match the app's sign-up form", () => {
  assert.ok(L.passwordProblem("short1!"));
  assert.ok(L.passwordProblem("NoDigits!!"));
  assert.ok(L.passwordProblem("NoSpecial1"));
  assert.strictEqual(L.passwordProblem("Bahgo#2026"), null);
});

test("hashes match what Android (javax.crypto PBKDF2WithHmacSHA256) produces", () => {
  // Vectors made with the JDK in Android Studio's runtime
  assert.strictEqual(L.hashPassword("Bahgo#2026", SALT, 100), "BbpP9BwMu6mcBWul2nX/BBwYNAMxp95VDt1+LSvJRKE=");
  assert.strictEqual(L.hashPassword("Bahgo#2026", SALT, 100000), "IKimeBRlWgn2ZYZDZEpN2VtgMVSwkCSE1HinJ5kL5Cg=");
});

test("old accounts (no iteration count) still verify, wrong passwords don't", () => {
  const legacy = { hash: "BbpP9BwMu6mcBWul2nX/BBwYNAMxp95VDt1+LSvJRKE=", salt: SALT };
  assert.strictEqual(L.verifyPassword("Bahgo#2026", legacy), true);
  assert.strictEqual(L.matchingIterations("Bahgo#2026", legacy), 100);
  assert.strictEqual(L.verifyPassword("wrong#2026", legacy), false);
  assert.strictEqual(L.verifyPassword("Bahgo#2026", null), false);
});

test("new credentials use a random salt and 100000 iterations", () => {
  const a = L.newCredentials("Bahgo#2026");
  const b = L.newCredentials("Bahgo#2026");
  assert.strictEqual(a.iterations, 100000);
  assert.notStrictEqual(a.salt, b.salt);
  assert.ok(L.verifyPassword("Bahgo#2026", a));
  assert.ok(!L.verifyPassword("Bahgo#2027", a));
});

test("codes: 6 digits, stored hashed, used once, 3 tries, 5-minute expiry", () => {
  const key = "639171234567";
  const code = L.newOtpCode();
  assert.match(code, /^\d{6}$/);
  const now = 1_800_000_000_000;
  const state = { codeHash: L.otpHash(code, key), expiresAt: now + L.OTP_TTL_MS, attempts: 0, purpose: "signup" };
  assert.notStrictEqual(state.codeHash, code);

  assert.strictEqual(L.checkOtp(state, code, key, "changePhone", now).ok, false);   // wrong purpose
  const ok = L.checkOtp(state, code, key, "signup", now + MIN);
  assert.strictEqual(ok.ok, true);
  assert.strictEqual(ok.next.codeHash, null);                                       // used up
  assert.strictEqual(L.checkOtp(ok.next, code, key, "signup", now + MIN).ok, false);

  assert.strictEqual(L.checkOtp(state, code, key, "signup", now + 6 * MIN).ok, false); // expired

  let s = state;
  for (let i = 0; i < 3; i++) s = L.checkOtp(s, "000000" === code ? "111111" : "000000", key, "signup", now).next;
  assert.strictEqual(s.codeHash, null);                                             // locked after 3
  assert.strictEqual(L.checkOtp(s, code, key, "signup", now).ok, false);
});

test("codes: 60-second cooldown and at most 5 per hour", () => {
  const now = 1_800_000_000_000;
  assert.strictEqual(L.canSend(null, now).ok, true);
  assert.strictEqual(L.canSend({ sentAt: now - 30 * 1000 }, now).ok, false);
  const fifth = L.canSend({ sentAt: now - 2 * MIN, hourStart: now - 10 * MIN, hourCount: 4 }, now);
  assert.strictEqual(fifth.ok, true);
  assert.strictEqual(fifth.hourCount, 5);
  assert.strictEqual(L.canSend({ sentAt: now - 2 * MIN, hourStart: now - 10 * MIN, hourCount: 5 }, now).ok, false);
  const nextHour = L.canSend({ sentAt: now - 2 * MIN, hourStart: now - 61 * MIN, hourCount: 5 }, now);
  assert.strictEqual(nextHour.ok, true);
  assert.strictEqual(nextHour.hourCount, 1);
});

test("login locks for 15 minutes after 5 wrong passwords", () => {
  const now = 1_800_000_000_000;
  let s = null;
  for (let i = 0; i < 4; i++) s = L.afterFailure(s, now);
  assert.strictEqual(L.lockedFor(s, now), 0);
  s = L.afterFailure(s, now);
  assert.strictEqual(L.lockedFor(s, now), 15);
  assert.strictEqual(L.lockedFor(s, now + 16 * MIN), 0);
  assert.strictEqual(L.afterFailure(s, now + 16 * MIN).failures, 1);   // counting starts again
});

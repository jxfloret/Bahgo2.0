/**
 * Account and SMS-code rules for the BahGo sign-in functions (plain Node, no Firebase),
 * so they can be tested with `npm test`.
 *
 * Passwords: PBKDF2-HMAC-SHA256, 32-byte key, Base64 - the same as the Android app used
 * (javax.crypto PBKDF2WithHmacSHA256, keyLength 256), so hashes made on phones still verify.
 */
"use strict";

const crypto = require("crypto");

const NEW_ITERATIONS = 100000;
// Iteration counts older app versions may have used (the login screen accepted all of these)
const LEGACY_ITERATIONS = [100, 1000, 10000, 65536, 100000, 120000, 310000];

const OTP_TTL_MS = 5 * 60 * 1000;          // a code is valid for 5 minutes
const OTP_MAX_ATTEMPTS = 3;                // wrong guesses before the code is thrown away
const OTP_COOLDOWN_MS = 60 * 1000;         // wait between two codes to the same number
const OTP_MAX_PER_HOUR = 5;

const LOGIN_MAX_FAILURES = 5;              // wrong passwords before a lock
const LOGIN_LOCK_MS = 15 * 60 * 1000;

/** 09171234567 / 9171234567 / 639171234567 / +63 917 123 4567 -> "+639171234567", or null. */
function toE164(input) {
  const digits = String(input || "").replace(/\D/g, "");
  let local;
  if (digits.startsWith("63")) local = digits.slice(2);
  else if (digits.startsWith("0")) local = digits.slice(1);
  else local = digits;
  // Philippine mobile numbers: 9XX XXX XXXX
  if (!/^9\d{9}$/.test(local)) return null;
  return "+63" + local;
}

/** Account key used in /users and /credentials: the E.164 number without "+". */
function keyOf(e164) {
  return e164.replace(/\D/g, "");
}

/** Same rules as the app's sign-up form. Returns the problem, or null if the password is OK. */
function passwordProblem(password) {
  const p = String(password || "");
  if (p.length < 8) return "Password must be at least 8 characters.";
  if (!/\d/.test(p)) return "Password must contain at least 1 number.";
  if (!/[^A-Za-z0-9]/.test(p)) return "Password must contain at least 1 special character.";
  return null;
}

function hashPassword(password, saltBase64, iterations) {
  const salt = Buffer.from(saltBase64, "base64");
  return crypto.pbkdf2Sync(Buffer.from(String(password), "utf8"), salt, iterations, 32, "sha256").toString("base64");
}

/** New salted hash for a password: { hash, salt, iterations }. */
function newCredentials(password) {
  const salt = crypto.randomBytes(16).toString("base64");
  return { hash: hashPassword(password, salt, NEW_ITERATIONS), salt, iterations: NEW_ITERATIONS };
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/**
 * True if [password] matches stored credentials. [cred] = { hash, salt, iterations? };
 * without iterations (old accounts) every legacy count is tried.
 */
function verifyPassword(password, cred) {
  if (!cred || !cred.hash || !cred.salt) return false;
  const counts = cred.iterations ? [cred.iterations] : LEGACY_ITERATIONS;
  return counts.some((n) => safeEqual(hashPassword(password, cred.salt, n), cred.hash));
}

/** Which iteration count matches (for moving old accounts), or null. */
function matchingIterations(password, cred) {
  if (!cred || !cred.hash || !cred.salt) return null;
  for (const n of LEGACY_ITERATIONS) {
    if (safeEqual(hashPassword(password, cred.salt, n), cred.hash)) return n;
  }
  return null;
}

// ───────────────────────── SMS codes ─────────────────────────

function newOtpCode() {
  return String(crypto.randomInt(100000, 1000000));
}

/** Codes are stored hashed with the number, so a database leak doesn't reveal them. */
function otpHash(code, key) {
  return crypto.createHash("sha256").update(`${key}:${String(code).trim()}`).digest("base64");
}

/**
 * May a new code be sent? [state] = what /otp/{key} holds ({ sentAt, hourStart, hourCount }).
 * Returns { ok, waitSeconds?, error? } and the hour counters to save.
 */
function canSend(state, now) {
  const s = state || {};
  if (s.sentAt && now - s.sentAt < OTP_COOLDOWN_MS) {
    const wait = Math.ceil((OTP_COOLDOWN_MS - (now - s.sentAt)) / 1000);
    return { ok: false, error: `Please wait ${wait} seconds before asking for a new code.` };
  }
  const sameHour = s.hourStart && now - s.hourStart < 60 * 60 * 1000;
  const hourCount = sameHour ? s.hourCount || 0 : 0;
  if (hourCount >= OTP_MAX_PER_HOUR) {
    return { ok: false, error: "Too many codes requested. Please try again in an hour." };
  }
  return { ok: true, hourStart: sameHour ? s.hourStart : now, hourCount: hourCount + 1 };
}

/**
 * Checks an entered code against /otp/{key}. Returns { ok, error?, next } where next is what
 * to save (null = delete the code).
 */
function checkOtp(state, code, key, purpose, now) {
  const s = state || {};
  if (!s.codeHash || s.purpose !== purpose) return { ok: false, error: "No code was sent. Ask for a new code.", next: s.codeHash ? s : null };
  if (now > s.expiresAt) return { ok: false, error: "The code expired. Ask for a new code.", next: { ...s, codeHash: null } };
  if ((s.attempts || 0) >= OTP_MAX_ATTEMPTS) return { ok: false, error: "Too many attempts. Ask for a new code.", next: { ...s, codeHash: null } };
  if (!safeEqual(otpHash(code, key), s.codeHash)) {
    const attempts = (s.attempts || 0) + 1;
    return {
      ok: false,
      error: attempts >= OTP_MAX_ATTEMPTS ? "Too many attempts. Ask for a new code." : "Incorrect code.",
      next: { ...s, attempts, codeHash: attempts >= OTP_MAX_ATTEMPTS ? null : s.codeHash }
    };
  }
  return { ok: true, next: { ...s, codeHash: null, attempts: 0 } };   // used up
}

// ───────────────────────── Login lockout ─────────────────────────

/** Is this account locked after too many wrong passwords? [state] = /loginAttempts/{key}. */
function lockedFor(state, now) {
  if (state && state.lockUntil && now < state.lockUntil) return Math.ceil((state.lockUntil - now) / 60000);
  return 0;
}

/** After a wrong password: the new /loginAttempts/{key}. */
function afterFailure(state, now) {
  const failures = (state && state.lockUntil && now >= state.lockUntil ? 0 : (state && state.failures) || 0) + 1;
  return failures >= LOGIN_MAX_FAILURES ? { failures: 0, lockUntil: now + LOGIN_LOCK_MS } : { failures, lockUntil: 0 };
}

module.exports = {
  NEW_ITERATIONS,
  LEGACY_ITERATIONS,
  OTP_TTL_MS,
  OTP_MAX_ATTEMPTS,
  OTP_COOLDOWN_MS,
  OTP_MAX_PER_HOUR,
  LOGIN_MAX_FAILURES,
  LOGIN_LOCK_MS,
  toE164,
  keyOf,
  passwordProblem,
  hashPassword,
  newCredentials,
  verifyPassword,
  matchingIterations,
  newOtpCode,
  otpHash,
  canSend,
  checkOtp,
  lockedFor,
  afterFailure,
};

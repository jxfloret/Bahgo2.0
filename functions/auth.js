/**
 * BahGo sign-in on the server (so the database no longer has to be public).
 *
 * The app calls these HTTPS endpoints (POST, JSON) and signs in to Firebase Authentication
 * with the custom token they return. Password hashes live in /credentials and SMS codes in
 * /otp, which only these functions can read (see database.rules.json).
 *
 *   sendOtp         { phone, purpose: "signup" | "changePhone" }            (changePhone: signed in)
 *   signUp          { phone, code, firstName, surname, password, hasVehicle, consent: { version, analytics } }
 *   login           { phone, password }
 *   changePassword  { current, newPassword }                                (signed in)
 *   changePhone     { password, newPhone, code }                            (signed in)
 *
 * Success: 200 { ok: true, token? }. Problem: 4xx/5xx { error: "message for the user" }.
 * Signed-in calls send "Authorization: Bearer <Firebase ID token>".
 * SMS gateway login: secrets SMSGATEWAY_USERNAME / SMSGATEWAY_PASSWORD (never in the app).
 */
"use strict";

const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const { logger } = require("firebase-functions/v2");
const admin = require("firebase-admin");
const L = require("./authLogic");

const SMS_USER = defineSecret("SMSGATEWAY_USERNAME");
const SMS_PASS = defineSecret("SMSGATEWAY_PASSWORD");
const SMS_URL = "https://api.sms-gate.app/3rdparty/v1/message";
const GENERIC_LOGIN_ERROR = "Incorrect number or password.";
// Every app sign-in carries role "user": the database rules let only admin/super/user read the
// live sensors, and only "device" write them (see database.rules.json).
const USER_CLAIMS = { role: "user" };

const db = () => admin.database();
const now = () => Date.now();

class UserError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** The signed-in account (uid = phone key), or null if no valid token was sent. */
async function callerUid(req) {
  const m = /^Bearer (.+)$/.exec(req.get("Authorization") || "");
  if (!m) return null;
  try {
    return (await admin.auth().verifyIdToken(m[1], true)).uid;
  } catch (_) {
    return null;
  }
}

/**
 * POST-only JSON endpoint with friendly errors.
 * auth: "required" (must be signed in), "optional" (uid if signed in) or "none".
 */
function endpoint(handler, { auth = "none", secrets = [] } = {}) {
  return onRequest({ secrets }, async (req, res) => {
    try {
      if (req.method !== "POST") throw new UserError(405, "Use POST.");
      const uid = auth === "none" ? null : await callerUid(req);
      if (auth === "required" && !uid) throw new UserError(401, "Please log in again.");
      const out = await handler(req.body || {}, uid);
      res.status(200).json({ ok: true, ...out });
    } catch (e) {
      if (e instanceof UserError) {
        res.status(e.status).json({ error: e.message });
      } else {
        logger.error("Auth endpoint failed", e);
        res.status(500).json({ error: "Something went wrong. Please try again." });
      }
    }
  });
}

function phoneOrFail(input) {
  const e164 = L.toE164(input);
  if (!e164) throw new UserError(400, "Enter a valid mobile number, e.g. 09171234567.");
  return e164;
}

function nameOrFail(value, label) {
  const v = String(value || "").trim();
  if (!v) throw new UserError(400, `Enter your ${label}.`);
  if (v.length > 60) throw new UserError(400, `Your ${label} is too long.`);
  return v;
}

function passwordOrFail(password) {
  const problem = L.passwordProblem(password);
  if (problem) throw new UserError(400, problem);
  return String(password);
}

async function isRegistered(key) {
  const [u, c] = await Promise.all([db().ref(`users/${key}`).get(), db().ref(`credentials/${key}`).get()]);
  return u.exists() || c.exists();
}

/** Password data for an account; old accounts keep their hash in /users until they log in. */
async function credentialsOf(key) {
  const c = await db().ref(`credentials/${key}`).get();
  if (c.exists()) return { cred: c.val(), legacy: false };
  const u = await db().ref(`users/${key}`).get();
  const hash = u.child("passwordHash").val();
  const salt = u.child("salt").val();
  return hash && salt ? { cred: { hash, salt }, legacy: true } : { cred: null, legacy: false };
}

/** Checks a password; old accounts are moved to /credentials on success. */
async function checkPassword(key, password) {
  const { cred, legacy } = await credentialsOf(key);
  if (!cred) return false;
  if (!legacy) return L.verifyPassword(password, cred);
  const iterations = L.matchingIterations(password, cred);
  if (iterations == null) return false;
  await db().ref().update({
    [`credentials/${key}`]: { hash: cred.hash, salt: cred.salt, iterations },
    [`users/${key}/passwordHash`]: null,
    [`users/${key}/salt`]: null,
  });
  return true;
}

async function useCode(key, code, purpose) {
  const ref = db().ref(`otp/${key}`);
  const result = L.checkOtp((await ref.get()).val(), code, key, purpose, now());
  await ref.set(result.next);
  if (!result.ok) throw new UserError(400, result.error);
}

async function sendSms(e164, text) {
  const basic = Buffer.from(`${SMS_USER.value()}:${SMS_PASS.value()}`).toString("base64");
  const resp = await fetch(SMS_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Basic ${basic}` },
    body: JSON.stringify({ textMessage: { text }, phoneNumbers: [e164] }),
  });
  if (!resp.ok) {
    logger.error(`SMS gateway answered HTTP ${resp.status}`);
    throw new UserError(502, "We couldn't send the SMS right now. Please try again later.");
  }
}

function logLogin(e164, status) {
  return db().ref("logins").push({ phoneNumber: e164, loginTime: admin.database.ServerValue.TIMESTAMP, status });
}

// ───────────────────────── Endpoints ─────────────────────────

/** Sends a 6-digit code to a number that isn't registered yet (sign-up, or changing your number). */
exports.sendOtp = endpoint(async (body, uid) => {
  const e164 = phoneOrFail(body.phone);
  const key = L.keyOf(e164);
  const purpose = body.purpose;
  if (purpose !== "signup" && purpose !== "changePhone") throw new UserError(400, "Unknown request.");
  if (purpose === "changePhone" && !uid) throw new UserError(401, "Please log in again.");
  if (await isRegistered(key)) {
    throw new UserError(409, purpose === "signup"
      ? "This number is already registered. Please log in."
      : "This number already has an account.");
  }

  const ref = db().ref(`otp/${key}`);
  const allowed = L.canSend((await ref.get()).val(), now());
  if (!allowed.ok) throw new UserError(429, allowed.error);

  const code = L.newOtpCode();
  await sendSms(e164, `Your BahGo verification code is ${code}. It expires in 5 minutes. Do not share it.`);
  await ref.set({
    codeHash: L.otpHash(code, key),
    expiresAt: now() + L.OTP_TTL_MS,
    attempts: 0,
    purpose,
    sentAt: now(),
    hourStart: allowed.hourStart,
    hourCount: allowed.hourCount,
  });
  return {};
}, { auth: "optional", secrets: [SMS_USER, SMS_PASS] });

/** Creates the account after the SMS code is confirmed, and signs the user in. */
exports.signUp = endpoint(async (body) => {
  const e164 = phoneOrFail(body.phone);
  const key = L.keyOf(e164);
  const firstName = nameOrFail(body.firstName, "first name");
  const surname = nameOrFail(body.surname, "surname");
  const password = passwordOrFail(body.password);
  if (await isRegistered(key)) throw new UserError(409, "This number is already registered. Please log in.");
  await useCode(key, body.code, "signup");

  const consent = body.consent && body.consent.version
    ? { version: String(body.consent.version), analytics: !!body.consent.analytics, acceptedAt: admin.database.ServerValue.TIMESTAMP }
    : null;
  await db().ref().update({
    [`users/${key}`]: {
      phoneNumber: key,
      firstName,
      surname,
      hasVehicle: !!body.hasVehicle,
      createdAt: admin.database.ServerValue.TIMESTAMP,
      ...(consent ? { consent } : {}),
    },
    [`credentials/${key}`]: L.newCredentials(password),
  });
  await logLogin(e164, "SIGNUP");
  return { token: await admin.auth().createCustomToken(key, USER_CLAIMS), phone: e164 };
});

/** Checks the password on the server and signs the user in. Locks after 5 wrong tries. */
exports.login = endpoint(async (body) => {
  const e164 = L.toE164(body.phone);
  if (!e164 || !body.password) throw new UserError(401, GENERIC_LOGIN_ERROR);
  const key = L.keyOf(e164);

  const attemptsRef = db().ref(`loginAttempts/${key}`);
  const attempts = (await attemptsRef.get()).val();
  const minutes = L.lockedFor(attempts, now());
  if (minutes > 0) throw new UserError(429, `Too many wrong passwords. Try again in ${minutes} min.`);

  if (!(await checkPassword(key, String(body.password)))) {
    await attemptsRef.set(L.afterFailure(attempts, now()));
    throw new UserError(401, GENERIC_LOGIN_ERROR);
  }
  await attemptsRef.remove();
  await logLogin(e164, "SUCCESS");
  return { token: await admin.auth().createCustomToken(key, USER_CLAIMS), phone: e164 };
});

/** Changes the signed-in user's password (needs the current one). */
exports.changePassword = endpoint(async (body, uid) => {
  const newPassword = passwordOrFail(body.newPassword);
  if (!(await checkPassword(uid, String(body.current || "")))) {
    throw new UserError(400, "Current password is incorrect.");
  }
  if (newPassword === String(body.current)) throw new UserError(400, "Use a different password from your current one.");
  await db().ref(`credentials/${uid}`).set(L.newCredentials(newPassword));
  return {};
}, { auth: "required" });

/**
 * Moves the signed-in account to a new number (password + the code sent to the new number),
 * then signs in as the new number.
 */
exports.changePhone = endpoint(async (body, uid) => {
  const e164 = phoneOrFail(body.newPhone);
  const newKey = L.keyOf(e164);
  if (newKey === uid) throw new UserError(400, "That's already your number.");
  if (!(await checkPassword(uid, String(body.password || "")))) throw new UserError(400, "Password is incorrect.");
  if (await isRegistered(newKey)) throw new UserError(409, "This number already has an account.");
  await useCode(newKey, body.code, "changePhone");

  const [user, cred] = await Promise.all([db().ref(`users/${uid}`).get(), db().ref(`credentials/${uid}`).get()]);
  if (!user.exists() || !cred.exists()) throw new UserError(404, "Account not found.");
  const profile = { ...user.val(), phoneNumber: newKey };
  delete profile.passwordHash;
  delete profile.salt;
  await db().ref().update({
    [`users/${newKey}`]: profile,
    [`credentials/${newKey}`]: cred.val(),
    [`users/${uid}`]: null,
    [`credentials/${uid}`]: null,
  });
  // The old number can't be used to sign in any more
  await admin.auth().revokeRefreshTokens(uid).catch(() => {});
  return { token: await admin.auth().createCustomToken(newKey, USER_CLAIMS), phone: e164 };
}, { auth: "required" });

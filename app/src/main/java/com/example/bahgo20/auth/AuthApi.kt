package com.example.bahgo20.auth

import android.util.Base64
import android.util.Log
import com.example.bahgo20.BuildConfig
import com.example.bahgo20.utils.Phone
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Sign-in, sign-up and SMS codes, done on the phone (works on the free Firebase Spark plan).
 *
 *   - Accounts are in /users/{639171234567}: firstName, surname, phoneNumber, hasVehicle,
 *     passwordHash + salt (PBKDF2-SHA256, 100,000 rounds), createdAt.
 *   - Codes are texted through SMS Gateway for Android (cloud mode). Its username/password
 *     come from gradle.properties / local.properties (SMSGATEWAY_USERNAME / _PASSWORD).
 *   - The app also signs in to Firebase anonymously ([ensureFirebaseSignIn]). The database
 *     rules only answer signed-in apps (or the team's admin/super/device accounts), so random
 *     people on the internet can't read the data.
 *
 * functions/auth.js is the server version (needs the Blaze plan); see docs/SECURE_LOGIN_DEPLOY.md.
 */
object AuthApi {
    private const val TAG = "AuthApi"
    private const val DB_URL = "https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/"
    private const val SMS_URL = "https://api.sms-gate.app/3rdparty/v1/message"

    private const val CODE_EXPIRY_MS = 5 * 60 * 1000L
    private const val RESEND_COOLDOWN_MS = 60 * 1000L
    private const val MAX_CODE_ATTEMPTS = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val random = SecureRandom()
    private val users get() = FirebaseDatabase.getInstance(DB_URL).reference.child("users")
    private val logins get() = FirebaseDatabase.getInstance(DB_URL).reference.child("logins")

    /** The signed-in account's key, set after log in / sign-up (used to change password or number). */
    @Volatile private var currentKey: String? = null

    class AuthException(message: String, val status: Int = 0) : Exception(message) {
        /** 409 = the number already has an account. */
        val alreadyRegistered: Boolean get() = status == 409
    }

    // ───────────────────────── Firebase sign-in (free, anonymous) ─────────────────────────

    /**
     * Makes sure this app is signed in to Firebase (anonymously, free on the Spark plan) before it
     * reads or writes the database. Firebase keeps the sign-in between app starts, so this only
     * goes online the first time. Returns false if it couldn't sign in (e.g. no internet).
     */
    suspend fun ensureFirebaseSignIn(): Boolean {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) return true
        return try {
            auth.signInAnonymously().await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous Firebase sign-in failed (is Anonymous turned on in Firebase Authentication?)", e)
            false
        }
    }

    // ───────────────────────── Codes (kept in memory until used) ─────────────────────────

    private class Pending(val code: String, val expiresAt: Long, var attempts: Int = 0)
    private val pending = mutableMapOf<String, Pending>()
    private val lastSent = mutableMapOf<String, Long>()

    /** Texts a 6-digit code. [purpose] "signup" or "changePhone": the number must not have an account yet. */
    suspend fun sendOtp(phone: String, purpose: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureFirebaseSignIn()
            val e164 = Phone.toE164(phone)
            val key = Phone.keyOf(phone)
            if (users.child(key).get().await().exists()) {
                throw AuthException(
                    if (purpose == "signup") "This number is already registered. Please log in."
                    else "This number already has an account.", 409
                )
            }
            val now = System.currentTimeMillis()
            lastSent[e164]?.let { last ->
                if (now - last < RESEND_COOLDOWN_MS) {
                    throw AuthException("Please wait ${(RESEND_COOLDOWN_MS - (now - last)) / 1000} seconds before resending.", 429)
                }
            }
            val code = (100000 + random.nextInt(900000)).toString()
            sendSms(e164, "Your Bahgo verification code is $code. It expires in 5 minutes. Do not share it.")
            synchronized(pending) {
                pending[e164] = Pending(code, now + CODE_EXPIRY_MS)
                lastSent[e164] = now
            }
        }.recoverNetwork()
    }

    private fun checkCode(phone: String, entered: String) {
        val e164 = Phone.toE164(phone)
        synchronized(pending) {
            val p = pending[e164] ?: throw AuthException("No code was sent to this number.")
            when {
                System.currentTimeMillis() > p.expiresAt -> { pending.remove(e164); throw AuthException("Code expired. Request a new one.") }
                p.attempts >= MAX_CODE_ATTEMPTS -> { pending.remove(e164); throw AuthException("Too many wrong attempts. Request a new code.") }
                entered.trim() != p.code -> { p.attempts++; throw AuthException("Incorrect code.") }
                else -> pending.remove(e164)
            }
        }
    }

    private fun sendSms(e164: String, text: String) {
        if (BuildConfig.SMSGATEWAY_USERNAME.isEmpty()) throw AuthException("SMS isn't set up in this build.")
        val body = JSONObject()
            .put("textMessage", JSONObject().put("text", text))
            .put("phoneNumbers", JSONArray().put(e164))
        val request = Request.Builder()
            .url(SMS_URL)
            .header("Authorization", Credentials.basic(BuildConfig.SMSGATEWAY_USERNAME, BuildConfig.SMSGATEWAY_PASSWORD))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "SMS failed (HTTP ${resp.code}): ${resp.body?.string()}")
                throw AuthException("Couldn't send the SMS (HTTP ${resp.code}). Please try again.", resp.code)
            }
        }
    }

    // ───────────────────────── Accounts ─────────────────────────

    /** Checks the number and password; returns the number as +63… */
    suspend fun login(phone: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureFirebaseSignIn()
            val e164 = Phone.toE164(phone)
            val key = Phone.keyOf(phone)
            val snap = users.child(key).get().await()
            val hash = snap.child("passwordHash").getValue(String::class.java)
            val salt = snap.child("salt").getValue(String::class.java)
            if (!snap.exists() || hash.isNullOrEmpty() || salt.isNullOrEmpty() || !matches(password, hash, salt)) {
                logLogin(e164, "FAILED")
                throw AuthException("Incorrect phone number or password.", 401)
            }
            logLogin(e164, "SUCCESS")
            currentKey = key
            e164
        }.recoverNetwork()
    }

    /** Checks the code and creates the account. Returns the number as +63… */
    suspend fun signUp(
        phone: String, code: String, firstName: String, surname: String,
        password: String, hasVehicle: Boolean, consentVersion: String?, analytics: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureFirebaseSignIn()
            checkCode(phone, code)
            val e164 = Phone.toE164(phone)
            val key = Phone.keyOf(phone)
            if (users.child(key).get().await().exists()) throw AuthException("This number is already registered. Please log in.", 409)
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val user = mutableMapOf<String, Any>(
                "phoneNumber" to key,
                "firstName" to firstName,
                "surname" to surname,
                "passwordHash" to hash(password, salt),
                "salt" to Base64.encodeToString(salt, Base64.NO_WRAP),
                "hasVehicle" to hasVehicle,
                "createdAt" to ServerValue.TIMESTAMP
            )
            if (consentVersion != null) {
                user["consent"] = mapOf("version" to consentVersion, "analytics" to analytics, "acceptedAt" to ServerValue.TIMESTAMP)
            }
            users.child(key).setValue(user).await()
            logLogin(e164, "SUCCESS")
            currentKey = key
            e164
        }.recoverNetwork()
    }

    /** Signed in: checks the current password, then saves the new one. */
    suspend fun changePassword(current: String, newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureFirebaseSignIn()
            val key = signedInKey()
            val snap = users.child(key).get().await()
            val hash = snap.child("passwordHash").getValue(String::class.java).orEmpty()
            val salt = snap.child("salt").getValue(String::class.java).orEmpty()
            if (hash.isEmpty() || !matches(current, hash, salt)) throw AuthException("Current password is incorrect.", 401)
            val newSalt = ByteArray(16).also { random.nextBytes(it) }
            users.child(key).updateChildren(
                mapOf("passwordHash" to hash(newPassword, newSalt), "salt" to Base64.encodeToString(newSalt, Base64.NO_WRAP))
            ).await()
            Unit
        }.recoverNetwork()
    }

    /** Signed in: moves the account to [newPhone] after checking the password and the code sent to it. */
    suspend fun changePhone(password: String, newPhone: String, code: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureFirebaseSignIn()
            val oldKey = signedInKey()
            val snap = users.child(oldKey).get().await()
            val hash = snap.child("passwordHash").getValue(String::class.java).orEmpty()
            val salt = snap.child("salt").getValue(String::class.java).orEmpty()
            if (hash.isEmpty() || !matches(password, hash, salt)) throw AuthException("Current password is incorrect.", 401)
            checkCode(newPhone, code)
            val newKey = Phone.keyOf(newPhone)
            if (users.child(newKey).get().await().exists()) throw AuthException("This number already has an account.", 409)
            @Suppress("UNCHECKED_CAST")
            val data = (snap.value as? Map<String, Any?>).orEmpty().toMutableMap()
            data["phoneNumber"] = newKey
            users.child(newKey).setValue(data).await()
            users.child(oldKey).removeValue().await()
            currentKey = newKey
            Phone.toE164(newPhone)
        }.recoverNetwork()
    }

    // ───────────────────────── Helpers ─────────────────────────

    private fun signedInKey(): String {
        currentKey?.let { return it }
        throw AuthException("Please log in again.")
    }

    /** Remembers who is signed in (call when the app starts with a saved session). */
    fun restore(phoneE164: String?) {
        currentKey = phoneE164?.filter { it.isDigit() }?.ifEmpty { null }
    }

    fun signOut() { currentKey = null }

    private fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    private fun matches(password: String, storedHash: String, storedSalt: String): Boolean = runCatching {
        val salt = Base64.decode(storedSalt, Base64.NO_WRAP)
        MessageDigest.isEqual(Base64.decode(storedHash, Base64.NO_WRAP), Base64.decode(hash(password, salt), Base64.NO_WRAP))
    }.getOrDefault(false)

    private fun logLogin(e164: String, status: String) {
        logins.push().setValue(mapOf("phoneNumber" to e164, "loginTime" to ServerValue.TIMESTAMP, "status" to status))
    }

    /** Turns network problems into one friendly message; keeps our own messages. */
    private fun <T> Result<T>.recoverNetwork(): Result<T> = recoverCatching { e ->
        if (e is AuthException) throw e
        Log.e(TAG, "Request failed", e)
        throw AuthException(
            if (e is java.io.IOException) "Can't reach BahGo. Check your internet connection."
            else "Something went wrong. Please try again."
        )
    }
}

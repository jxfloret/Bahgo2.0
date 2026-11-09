package com.example.bahgo20.utils

import com.example.bahgo20.R
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * The signed-in user's profile in Firebase: /users/{phone} with firstName, surname, hasVehicle,
 * phoneNumber (phone = digits, e.g. 639171234567). Only the signed-in user can read or change it.
 */
object AccountRepository {
    private const val DB_URL = "https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/"
    private val users get() = FirebaseDatabase.getInstance(DB_URL).reference.child("users")

    data class Profile(val firstName: String, val surname: String)

    /** Same password rules as sign-up. Returns the problem, or null if the password is OK. */
    /** What's wrong with a new password (a string resource), or null if it's fine. */
    fun passwordProblem(password: String): Int? = when {
        password.length < 8 -> R.string.password_too_short
        !password.any { it.isDigit() } -> R.string.password_needs_number
        !password.any { !it.isLetterOrDigit() } -> R.string.password_needs_special
        else -> null
    }

    suspend fun loadProfile(phoneKey: String): Profile? {
        val snap = users.child(phoneKey).get().await()
        if (!snap.exists()) return null
        return Profile(
            snap.child("firstName").getValue(String::class.java).orEmpty(),
            snap.child("surname").getValue(String::class.java).orEmpty()
        )
    }

    suspend fun updateName(phoneKey: String, firstName: String, surname: String) {
        users.child(phoneKey).updateChildren(mapOf("firstName" to firstName, "surname" to surname)).await()
    }

    // Passwords and phone-number changes are handled on the server (see auth/AuthApi).
}

package com.example.bahgo20.utils

/** Philippine mobile numbers, the same way the server reads them (functions/authLogic.js). */
object Phone {
    /** 09171234567 / 9171234567 / 639171234567 -> +639171234567 */
    fun toE164(input: String): String {
        val digits = input.filter { it.isDigit() }
        return when {
            digits.startsWith("63") -> "+$digits"
            digits.startsWith("0") -> "+63${digits.drop(1)}"
            else -> "+63$digits"
        }
    }

    /** The account key in the database: the number without "+", e.g. 639171234567. */
    fun keyOf(input: String): String = toE164(input).filter { it.isDigit() }
}

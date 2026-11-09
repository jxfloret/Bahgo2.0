package com.example.bahgo20.utils

import androidx.core.view.WindowInsetsCompat

/**
 * Bottom space to leave free: the keyboard's height while it is open, otherwise the
 * navigation bar. Edge-to-edge screens don't shrink for the keyboard on their own, so
 * padding by this lets their ScrollView shrink and scroll the typed-in field into view.
 */
fun WindowInsetsCompat.bottomAboveKeyboard(): Int = maxOf(
    getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
    getInsets(WindowInsetsCompat.Type.ime()).bottom
)

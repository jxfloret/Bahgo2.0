package com.example.bahgo20.utils

import android.content.Intent
import android.net.Uri

/**
 * The rescue team BahGo's "call" buttons dial (chat Emergency answer, Iwas-Baha Route when no
 * route is safe). Change the number here and every button follows.
 */
object EmergencyContact {
    const val NAME = "Southside BERT Rescue Team"
    const val SHORT_NAME = "Southside BERT"
    const val NUMBER = "09989750368"
    /** Easier to read: 0998 975 0368 */
    const val NUMBER_DISPLAY = "0998 975 0368"

    /** Opens the phone's dialer with the number filled in (the user taps Call). */
    fun dialIntent(): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$NUMBER"))
}

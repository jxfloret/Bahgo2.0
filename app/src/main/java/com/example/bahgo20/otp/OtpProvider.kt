package com.example.bahgo20.otp

import com.example.bahgo20.BuildConfig

/**
 * One shared SmsGatewayOtp for the whole app.
 * The login screen sends the code, and OtpActivity verifies it, so both
 * must use the same instance (it holds the pending code in memory).
 */
object OtpProvider {
    val otp: SmsGatewayOtp by lazy { 
        SmsGatewayOtp(
            username = BuildConfig.SMSGATEWAY_USERNAME,
            password = BuildConfig.SMSGATEWAY_PASSWORD
        )
    }
}

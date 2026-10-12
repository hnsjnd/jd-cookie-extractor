package com.example.jdcookie

data class CookieRecord(
    val ptKey: String,
    val ptPin: String,
    val savedAt: Long = System.currentTimeMillis()
) {
    val combined: String
        get() = "pt_key=$ptKey;pt_pin=$ptPin;"

    val displayPin: String
        get() = try {
            java.net.URLDecoder.decode(ptPin, "UTF-8")
        } catch (_: Exception) {
            ptPin
        }
}

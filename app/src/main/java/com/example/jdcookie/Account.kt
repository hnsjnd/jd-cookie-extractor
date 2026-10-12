package com.example.jdcookie

enum class AccountStatus {
    UNKNOWN,
    VALID,
    EXPIRED,
    CHECKING,
    ERROR
}

data class Account(
    val id: String,
    var nickname: String,
    val ptPin: String,
    var ptKey: String,
    var status: AccountStatus = AccountStatus.UNKNOWN,
    var lastCheckedAt: Long = 0L,
    var updatedAt: Long = System.currentTimeMillis(),
    var createdAt: Long = System.currentTimeMillis(),
    var lastMessage: String = ""
) {
    val combined: String
        get() = "pt_key=$ptKey;pt_pin=$ptPin;"

    val displayName: String
        get() = nickname.ifBlank {
            try {
                java.net.URLDecoder.decode(ptPin, "UTF-8")
            } catch (_: Exception) {
                ptPin
            }
        }

    val decodedPin: String
        get() = try {
            java.net.URLDecoder.decode(ptPin, "UTF-8")
        } catch (_: Exception) {
            ptPin
        }
}

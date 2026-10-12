package com.example.jdcookie

enum class AccountStatus {
    UNKNOWN,
    VALID,
    EXPIRED,
    CHECKING,
    ERROR
}

enum class PushStatus {
    NONE,       // 未同步
    SYNCED,     // 已同步
    FAILED,     // 失败
    PUSHING     // 推送中
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
    var lastMessage: String = "",
    var pushStatus: PushStatus = PushStatus.NONE,
    var lastPushedAt: Long = 0L,
    var lastPushMessage: String = "",
    var qlEnvId: String = ""
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

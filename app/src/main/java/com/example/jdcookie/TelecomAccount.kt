package com.example.jdcookie

data class TelecomAccount(
    val id: String,
    var nickname: String,
    /** Value: 手机号#服务密码 */
    var credential: String,
    var pushStatus: PushStatus = PushStatus.NONE,
    var lastPushedAt: Long = 0L,
    var lastPushMessage: String = "",
    var qlEnvId: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var createdAt: Long = System.currentTimeMillis()
) {
    val phone: String
        get() = TelecomAccountStore.extractPhone(credential)

    val displayName: String
        get() = nickname.ifBlank { phone.ifBlank { "电信账号" } }

    /** Masked preview — never show full service password in UI list. */
    val credentialPreview: String
        get() {
            val phonePart = phone
            if (phonePart.isBlank()) return "••••"
            return "$phonePart#••••"
        }
}

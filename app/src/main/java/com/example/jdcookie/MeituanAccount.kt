package com.example.jdcookie

data class MeituanAccount(
    val id: String,
    var nickname: String,
    var token: String,
    var pushStatus: PushStatus = PushStatus.NONE,
    var lastPushedAt: Long = 0L,
    var lastPushMessage: String = "",
    var qlEnvId: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var createdAt: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = nickname.ifBlank {
            if (token.length > 12) token.take(8) + "…" else token
        }

    val tokenPreview: String
        get() = when {
            token.isBlank() -> ""
            token.length <= 16 -> token
            else -> token.take(10) + "…" + token.takeLast(4)
        }
}

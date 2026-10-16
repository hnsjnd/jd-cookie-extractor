package com.example.jdcookie

data class ElmAccount(
    val id: String,
    var nickname: String,
    var cookie: String,
    var pushStatus: PushStatus = PushStatus.NONE,
    var lastPushedAt: Long = 0L,
    var lastPushMessage: String = "",
    var qlEnvId: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    var createdAt: Long = System.currentTimeMillis()
) {
    val userId: String
        get() = ElmAccountStore.extractField(cookie, "USERID")
            .ifBlank { ElmAccountStore.extractField(cookie, "unb") }

    val displayName: String
        get() = nickname.ifBlank {
            userId.ifBlank {
                val preview = cookie.take(20)
                if (cookie.length > 20) "$preview…" else preview
            }
        }

    val cookiePreview: String
        get() = when {
            cookie.isBlank() -> ""
            cookie.length <= 28 -> cookie
            else -> cookie.take(16) + "…" + cookie.takeLast(6)
        }
}

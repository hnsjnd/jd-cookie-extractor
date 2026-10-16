package com.example.jdcookie

data class QuarkAccount(
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
    /** Prefer user= field, then nickname. */
    val userField: String
        get() = QuarkAccountStore.extractField(cookie, "user")

    val displayName: String
        get() = nickname.ifBlank {
            userField.ifBlank {
                val preview = cookie.take(24)
                if (cookie.length > 24) "$preview…" else preview
            }
        }

    val cookiePreview: String
        get() = when {
            cookie.isBlank() -> ""
            cookie.length <= 28 -> cookie
            else -> cookie.take(16) + "…" + cookie.takeLast(6)
        }
}

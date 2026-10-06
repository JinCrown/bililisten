package app.bililisten.platform

/** Local cookie observation only. A candidate still requires server verification before saving. */
internal class LoginSessionWatch {
    private var previous: String? = null
    private var attempts = 0
    private var nextAt = 0L

    fun next(raw: String, nowMs: Long): String? {
        val cookie = selectedCookie(raw) ?: return null
        if (cookie != previous) { previous = cookie; attempts = 0; nextAt = 0 }
        if (attempts >= 3 || nowMs < nextAt) return null
        attempts++
        // A successful attempt closes the screen; a failed one may wait for cookies to settle.
        nextAt = nowMs + if (attempts == 1) 3000 else 10000
        return cookie
    }

    fun stop() { attempts = 3 }

    companion object {
        fun selectedCookie(raw: String): String? {
            val allowed = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
            val selected = raw.split(';').map { it.trim() }.filter { it.substringBefore('=') in allowed && '=' in it }
                .sortedBy { it.substringBefore('=') }
            if (selected.none { it.substringBefore('=') == "SESSDATA" && it.substringAfter('=').isNotBlank() }) return null
            return selected.joinToString("; ")
        }
    }
}

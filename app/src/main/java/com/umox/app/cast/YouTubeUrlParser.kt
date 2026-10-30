package com.umox.app.cast

import java.util.regex.Pattern

object YouTubeUrlParser {
    private val YOUTUBE_PATTERN = Pattern.compile(
        "(?:youtu\\.be\\/|youtube\\.com\\/(?:embed\\/|v\\/|watch\\?v=|watch\\?.+&v=|shorts\\/))([a-zA-Z0-9_-]{11})"
    )

    fun extractVideoId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()

        // Jika langsung 11 karakter video id
        if (trimmed.length == 11 && trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return trimmed
        }

        val matcher = YOUTUBE_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }
}

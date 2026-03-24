package com.narchives.reader.ui.util

import java.net.URI

fun extractDomain(url: String): String {
    return try {
        URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }
}

fun truncateUrl(url: String, maxLength: Int = 60): String {
    if (url.length <= maxLength) return url
    return url.take(maxLength - 3) + "..."
}

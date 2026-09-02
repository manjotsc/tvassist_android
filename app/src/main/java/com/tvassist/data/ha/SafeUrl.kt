package com.tvassist.data.ha

/**
 * `scheme://user:password@` in a URL's authority. Anchored to the `://` so a path segment that
 * merely contains an `@` (the character class stops at `/`) is left alone.
 */
internal val URL_USERINFO = Regex("""([a-zA-Z][a-zA-Z0-9+.\-]*://)[^/@\s]+@""")

/** What takes a secret's place. Visibly not a value, so nobody tries to use one. */
internal const val REDACTED = "«redacted»"

/**
 * A URL with any `user:password@` stripped, for building a log line out of.
 *
 * Camera URLs routinely carry credentials in the authority — `rtsp://admin:hunter2@cam/stream2` is
 * the shape most camera UIs hand you — and both players print the URL they failed on. Anything
 * logged can be read over adb, screenshotted, or sent to whoever is helping, so the credential has
 * to be gone before the line exists rather than filtered afterwards.
 *
 * Lives here rather than with the log viewer that used to own it: this is a rule about what the app
 * is allowed to write down, and it applies whether or not anything is reading the log back.
 */
fun safeUrlForLog(url: String): String = URL_USERINFO.replace(url, "$1$REDACTED@")

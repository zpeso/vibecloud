package dev.vibecloud.common.logging

interface Logger {
    fun log(level: LogLevel, message: String, cause: Throwable? = null)

    /** Updates an in-place progress line (e.g. a download bar). No-op by default. */
    fun updateProgressLine(text: String) = Unit

    /** Ends the current progress line. No-op by default. */
    fun clearProgressLine() = Unit

    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String, cause: Throwable? = null) = log(LogLevel.WARN, message, cause)
    fun error(message: String, cause: Throwable? = null) = log(LogLevel.ERROR, message, cause)
}

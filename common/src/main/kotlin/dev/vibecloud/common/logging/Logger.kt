package dev.vibecloud.common.logging

interface Logger {
    fun log(level: LogLevel, message: String, cause: Throwable? = null)

    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String, cause: Throwable? = null) = log(LogLevel.WARN, message, cause)
    fun error(message: String, cause: Throwable? = null) = log(LogLevel.ERROR, message, cause)
}

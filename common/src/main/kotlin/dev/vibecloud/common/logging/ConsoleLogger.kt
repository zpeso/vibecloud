package dev.vibecloud.common.logging

import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ConsoleLogger(
    private val minimumLevel: LogLevel = LogLevel.INFO,
    private val stdout: PrintStream = System.out,
    private val stderr: PrintStream = System.err,
) : Logger {

    /** When set, formatted lines are handed to this sink instead of the raw streams (interactive UI). */
    @Volatile
    var sink: ((String) -> Unit)? = null

    private val lock = Any()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    override fun log(level: LogLevel, message: String, cause: Throwable?) {
        if (level.ordinal < minimumLevel.ordinal) return
        val target = if (level.ordinal >= LogLevel.WARN.ordinal) stderr else stdout
        val line = "[${LocalTime.now().format(timeFormat)} ${level.name}] $message"
        synchronized(lock) {
            sink?.let {
                it(line)
                cause?.printStackTrace()
            } ?: run {
                target.println(line)
                cause?.printStackTrace(target)
                target.flush()
            }
        }
    }
}

package com.spacexjr.part2dump.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object P2DLog {

    const val TAG = "Part2Dump"

    private const val LOG_FILE_NAME = "part2dump.log"
    private const val MAX_LOG_BYTES = 512L * 1024L

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun attach(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            val appContext = context.applicationContext
            logFile = File(appContext.filesDir, LOG_FILE_NAME)
        }
    }

    fun logFile(): File? = logFile

    fun d(message: String) = write("D", message, null)

    fun i(message: String) = write("I", message, null)

    fun w(message: String) = write("W", message, null)

    fun w(message: String, error: Throwable) = write("W", message, error)

    fun e(message: String) = write("E", message, null)

    fun e(message: String, error: Throwable) = write("E", message, error)

    private fun write(level: String, message: String, error: Throwable?) {
        val safeMessage = buildString {
            append(message)
            if (error != null) {
                append(" | ")
                append(error.javaClass.simpleName)
                error.message?.let { append(": ").append(it) }
            }
        }
        when (level) {
            "E" -> Log.e(TAG, safeMessage)
            "W" -> Log.w(TAG, safeMessage)
            "I" -> Log.i(TAG, safeMessage)
            else -> Log.d(TAG, safeMessage)
        }
        appendToFile(level, safeMessage, error)
    }

    private fun appendToFile(level: String, message: String, error: Throwable?) {
        val target = logFile ?: return
        synchronized(lock) {
            try {
                if (target.exists() && target.length() > MAX_LOG_BYTES) {
                    target.delete()
                }
                val builder = StringBuilder()
                builder.append(timestampFormat.format(Date()))
                builder.append(' ')
                builder.append(level)
                builder.append('/')
                builder.append(TAG)
                builder.append(": ")
                builder.append(message)
                builder.append('\n')
                if (error != null) {
                    builder.append(Log.getStackTraceString(error))
                    builder.append('\n')
                }
                target.appendText(builder.toString())
            } catch (ignored: Throwable) {
            }
        }
    }
}
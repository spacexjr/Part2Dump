package com.spacexjr.part2dump.root

import com.spacexjr.part2dump.core.P2DLog
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShellException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut

    fun trimmedStdout(): String = stdout.trim()

    fun outputLines(): List<String> =
        stdout.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}

internal class StreamDrain(
    private val stream: InputStream?,
    private val onLine: ((String) -> Unit)?
) : Thread("p2d-shell-drain") {

    init {
        isDaemon = true
    }

    override fun run() {
        val source = stream ?: return
        try {
            BufferedReader(InputStreamReader(source)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (!line.isEmpty()) {
                        onLine?.invoke(line)
                    }
                    line = reader.readLine()
                }
            }
        } catch (error: IOException) {
            P2DLog.d("Stream encerrado: ${error.message}")
        }
    }
}

class ShellSession internal constructor(
    private val process: Process,
    private val stdoutDrain: StreamDrain?,
    private val stderrDrain: StreamDrain?
) {

    private val exitLatch = CountDownLatch(1)

    @Volatile
    var exitCode: Int = -1
        private set

    @Volatile
    var isFinished: Boolean = false
        private set

    init {
        Thread({
            try {
                exitCode = process.waitFor()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                isFinished = true
                exitLatch.countDown()
            }
        }, "p2d-shell-exit").apply {
            isDaemon = true
            start()
        }
    }

    fun await(timeoutMs: Long): Int? {
        val finished = exitLatch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        if (!finished) return null
        joinDrains()
        return exitCode
    }

    fun destroy() {
        process.destroy()
        joinDrains()
    }

    private fun joinDrains() {
        stdoutDrain?.join(DRAIN_JOIN_TIMEOUT_MS)
        stderrDrain?.join(DRAIN_JOIN_TIMEOUT_MS)
    }

    private companion object {
        const val DRAIN_JOIN_TIMEOUT_MS = 5000L
    }
}

class Shell(private val argv: List<String>, val label: String) {

    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val session = start(
            command,
            onStdout = { line -> out.append(line).append('\n') },
            onStderr = { line -> err.append(line).append('\n') }
        )
        val code = session.await(timeoutMs)
        if (code == null) {
            P2DLog.w("Tempo esgotado ($label): $command")
            session.destroy()
            return ShellResult(-1, out.toString(), err.toString(), timedOut = true)
        }
        return ShellResult(code, out.toString(), err.toString())
    }

    fun execLines(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<String> =
        exec(command, timeoutMs).outputLines()

    fun start(
        command: String,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null
    ): ShellSession {
        P2DLog.d("[$label] $command")
        val builder = ProcessBuilder(argv + command)
        builder.redirectErrorStream(false)
        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw ShellException("Não foi possível executar ${argv.firstOrNull()}: ${error.message}", error)
        } catch (error: SecurityException) {
            throw ShellException("Sem permissão para executar ${argv.firstOrNull()}", error)
        }
        val stdoutDrain = StreamDrain(process.inputStream, onStdout)
        val stderrDrain = StreamDrain(process.errorStream, onStderr)
        stdoutDrain.start()
        stderrDrain.start()
        return ShellSession(process, stdoutDrain, stderrDrain)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val QUICK_TIMEOUT_MS = 8_000L

        val SU_CANDIDATES = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
            "/magisk/su",
            "/system/bin/.ext/.su",
            "/system/ext/bin/su"
        )

        fun direct(): Shell = Shell(listOf("sh", "-c"), "shell")

        fun viaSu(binary: String): Shell = Shell(listOf(binary, "-c"), "su($binary)")

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun uidPattern(text: String): Int? {
            val first = text.trim().split('\n').firstOrNull()?.trim() ?: return null
            first.toIntOrNull()?.let { return it }
            val match = Regex("uid=(\\d+)").find(first) ?: return null
            return match.groupValues[1].toIntOrNull()
        }
    }
}
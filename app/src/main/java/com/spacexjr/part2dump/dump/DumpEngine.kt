package com.spacexjr.part2dump.dump

import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.partition.Partition
import com.spacexjr.part2dump.root.Shell
import com.spacexjr.part2dump.root.ShellException
import com.spacexjr.part2dump.root.ShellSession
import java.io.File
import java.util.Locale

class DumpEngine(private val shell: Shell) {

    @Volatile
    private var activeSession: ShellSession? = null

    @Volatile
    private var activeDevicePath: String? = null

    @Volatile
    private var cancelRequested = false

    fun isCancelRequested(): Boolean = cancelRequested

    fun resetCancel() {
        cancelRequested = false
    }

    fun requestCancel() {
        cancelRequested = true
        val session = activeSession ?: return
        val devicePath = activeDevicePath
        P2DLog.i("Cancelamento solicitado para $devicePath")
        session.destroy()
        if (devicePath != null) terminateDanglingDd(devicePath)
    }

    fun dump(
        partition: Partition,
        outputFile: File,
        onProgress: (Long) -> Unit,
        onLog: (String) -> Unit
    ): DumpOutcome {
        val startedAt = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - startedAt }

        validate(partition, outputFile)?.let { error ->
            return DumpOutcome.failure(error, "alvo inválido: ${partition.name}", elapsed())
        }
        if (isCancelRequested()) return DumpOutcome.failure(DumpError.CANCELLED, durationMs = elapsed())

        val directory = outputFile.parentFile
        if (directory == null || !directory.isDirectory && !directory.mkdirs()) {
            return DumpOutcome.failure(DumpError.OUTPUT_CREATE_FAILED, directory?.absolutePath, elapsed())
        }
        if (outputFile.exists() && !outputFile.delete()) {
            return DumpOutcome.failure(DumpError.OUTPUT_CREATE_FAILED, outputFile.absolutePath, elapsed())
        }

        val totalBytes = if (partition.hasKnownSize) partition.sizeBytes else -1L
        var usedFallback = false
        var attempt = runDd(partition, outputFile, BLOCK_SIZE_LARGE, useStatusProgress = true, totalBytes, onProgress, onLog)

        if (!attempt.success && attempt.statusOptionUnsupported) {
            onLog("dd sem suporte a status=progress, usando leitura por tamanho do arquivo")
            usedFallback = true
            attempt = runDd(partition, outputFile, BLOCK_SIZE_LARGE, useStatusProgress = false, totalBytes, onProgress, onLog)
        }
        if (!attempt.success && attempt.blockSizeUnsupported && usedFallback) {
            onLog("dd sem suporte a bs=$BLOCK_SIZE_LARGE, tentando bs=$BLOCK_SIZE_SAFE")
            attempt = runDd(partition, outputFile, BLOCK_SIZE_SAFE, useStatusProgress = false, totalBytes, onProgress, onLog)
        }

        if (isCancelRequested()) {
            deleteQuietly(outputFile)
            return DumpOutcome.failure(DumpError.CANCELLED, durationMs = elapsed())
        }
        if (attempt.timedOut) {
            deleteQuietly(outputFile)
            return DumpOutcome.failure(DumpError.TIMEOUT, attempt.output.trim().takeLast(400), elapsed())
        }
        if (attempt.exitCode != 0) {
            deleteQuietly(outputFile)
            val detail = attempt.output.trim()
            val error = if (attempt.output.contains("Permission denied", ignoreCase = true) ||
                attempt.output.contains("Operation not permitted", ignoreCase = true)
            ) DumpError.UNABLE_TO_READ else DumpError.DD_FAILED
            return DumpOutcome.failure(error, detail.takeLast(400), elapsed())
        }

        val writtenSize = resolveWrittenSize(outputFile, attempt.reportedBytes)
        if (totalBytes > 0 && writtenSize != totalBytes) {
            deleteQuietly(outputFile)
            return DumpOutcome.failure(
                DumpError.SIZE_MISMATCH,
                "esperado ${totalBytes} B, obtido ${if (writtenSize > 0) writtenSize else -1L} B",
                elapsed()
            )
        }
        if (writtenSize <= 0) {
            deleteQuietly(outputFile)
            return DumpOutcome.failure(DumpError.DD_FAILED, "arquivo de saída vazio", elapsed())
        }

        flushToDisk(outputFile)
        handOverOwnership(outputFile)
        onProgress(writtenSize)
        return DumpOutcome(true, writtenSize, DumpError.NONE, null, elapsed(), usedFallback)
    }

    private fun validate(partition: Partition, outputFile: File): DumpError? {
        val device = partition.devicePath
        if (device.isBlank() || !device.startsWith(DEVICE_PREFIX)) return DumpError.INVALID_TARGET
        if (device.contains("..")) return DumpError.INVALID_TARGET
        if (!SAFE_NAME.matches(partition.name)) return DumpError.INVALID_TARGET
        val output = outputFile.absolutePath
        if (output.startsWith("/dev/")) return DumpError.INVALID_TARGET
        if (!output.endsWith(".img")) return DumpError.INVALID_TARGET
        if (!File(device).exists()) return DumpError.PARTITION_MISSING
        return null
    }

    private fun runDd(
        partition: Partition,
        outputFile: File,
        blockSize: String,
        useStatusProgress: Boolean,
        totalBytes: Long,
        onProgress: (Long) -> Unit,
        onLog: (String) -> Unit
    ): DdAttempt {
        val command = buildDdCommand(partition.devicePath, outputFile.absolutePath, blockSize, useStatusProgress)
        var reported = 0L
        val reporter = ProgressReporter(totalBytes, onProgress)
        val stderrTail = StringBuilder()
        val stdoutTail = StringBuilder()

        val session = try {
            shell.start(
                command,
                onStdout = { line ->
                    onLog(line)
                    appendBounded(stdoutTail, line)
                    parseByteRecord(line)?.let { reported = it; reporter.report(it) }
                },
                onStderr = { line ->
                    onLog(line)
                    appendBounded(stderrTail, line)
                    parseByteRecord(line)?.let { reported = it; reporter.report(it) }
                }
            )
        } catch (error: ShellException) {
            P2DLog.e("Não foi possível iniciar dd", error)
            return DdAttempt(-1, false, 0L, error.message.orEmpty(), false, false)
        }

        activeSession = session
        activeDevicePath = partition.devicePath

        var poller: Thread? = null
        if (!useStatusProgress) {
            val target = outputFile
            poller = Thread({
                while (!session.isFinished) {
                    val size = readSize(target)
                    if (size > 0) reporter.report(size)
                    try {
                        Thread.sleep(POLL_INTERVAL_MS)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "p2d-dd-poller").apply {
                isDaemon = true
                start()
            }
        }

        val exitCode = session.await(DUMP_TIMEOUT_MS)
        if (exitCode == null) {
            session.destroy()
            terminateDanglingDd(partition.devicePath)
        }
        poller?.join(POLL_JOIN_TIMEOUT_MS)
        activeSession = null
        activeDevicePath = null

        val stderrText = stderrTail.toString()
        val combined = stderrText + "\n" + stdoutTail.toString()
        return DdAttempt(
            exitCode = exitCode ?: -1,
            timedOut = exitCode == null,
            reportedBytes = reported,
            output = combined,
            statusOptionUnsupported = isUnsupportedOption(combined, "status=progress"),
            blockSizeUnsupported = isUnsupportedOption(combined, "bs=$blockSize")
        )
    }

    private fun appendBounded(target: StringBuilder, line: String) {
        if (target.length > TAIL_LIMIT) return
        target.append(line.take(TAIL_LIMIT)).append('\n')
    }

    private fun buildDdCommand(
        devicePath: String,
        outputPath: String,
        blockSize: String,
        useStatusProgress: Boolean
    ): String {
        val builder = StringBuilder("dd if=")
        builder.append(Shell.quote(devicePath))
        builder.append(" of=")
        builder.append(Shell.quote(outputPath))
        builder.append(" bs=")
        builder.append(blockSize)
        if (useStatusProgress) builder.append(" status=progress")
        return builder.toString()
    }

    private fun resolveWrittenSize(outputFile: File, reportedBytes: Long): Long {
        val local = try {
            outputFile.length()
        } catch (error: Exception) {
            0L
        }
        if (local > 0L) return local
        val viaRoot = readSize(outputFile)
        if (viaRoot > 0L) return viaRoot
        return reportedBytes
    }

    private fun readSize(file: File): Long {
        val local = try {
            file.length()
        } catch (error: Exception) {
            0L
        }
        if (local > 0L) return local
        val quoted = Shell.quote(file.absolutePath)
        val stat = try {
            shell.exec("stat -c %s $quoted 2>/dev/null", Shell.QUICK_TIMEOUT_MS).trimmedStdout()
        } catch (error: ShellException) {
            ""
        }
        stat.toLongOrNull()?.let { if (it > 0L) return it }
        val wc = try {
            shell.exec("wc -c < $quoted 2>/dev/null", Shell.QUICK_TIMEOUT_MS).trimmedStdout()
        } catch (error: ShellException) {
            ""
        }
        return wc.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    private fun flushToDisk(file: File) {
        try {
            shell.exec("sync " + Shell.quote(file.absolutePath) + " 2>/dev/null", Shell.QUICK_TIMEOUT_MS)
        } catch (error: ShellException) {
            P2DLog.d("sync indisponível")
        }
    }

    private fun handOverOwnership(file: File) {
        val uid = android.os.Process.myUid()
        try {
            shell.exec("chown $uid:$uid " + Shell.quote(file.absolutePath) + " 2>/dev/null", Shell.QUICK_TIMEOUT_MS)
            shell.exec("chmod 0644 " + Shell.quote(file.absolutePath) + " 2>/dev/null", Shell.QUICK_TIMEOUT_MS)
        } catch (error: ShellException) {
            P2DLog.w("Não foi possível ajustar permissão de ${file.name}")
        }
    }

    private fun terminateDanglingDd(devicePath: String) {
        try {
            val needle = Shell.quote("if=" + devicePath)
            val listing = shell.exec(
                "ps -o PID,ARGS 2>/dev/null | grep -F $needle | grep -v grep",
                Shell.QUICK_TIMEOUT_MS
            ).outputLines()
            for (line in listing) {
                val pid = line.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: continue
                if (pid <= 1) continue
                shell.exec("kill -TERM $pid 2>/dev/null", Shell.QUICK_TIMEOUT_MS)
                P2DLog.i("dd encerrado (pid $pid)")
            }
            if (listing.isEmpty()) {
                P2DLog.d("Nenhum dd pendente para $devicePath")
            }
        } catch (error: ShellException) {
            P2DLog.w("Não foi possível encerrar dd pendente", error)
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists() && !file.delete()) {
                P2DLog.w("Não foi possível remover ${file.absolutePath}")
            }
        } catch (error: Exception) {
            P2DLog.w("Falha ao remover ${file.absolutePath}", error)
        }
    }

    private fun parseByteRecord(line: String): Long? {
        val cleaned = line.replace(",", "")
        val match = BYTE_RECORD.find(cleaned) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private class ProgressReporter(
        private val totalBytes: Long,
        private val onProgress: (Long) -> Unit
    ) {
        private var lastReported = 0L

        fun report(bytes: Long) {
            if (bytes <= 0L) return
            if (totalBytes > 0L) {
                val step = (totalBytes / 200L).coerceAtLeast(MIN_STEP_BYTES)
                if (bytes < lastReported + step && bytes < totalBytes) return
            } else if (bytes < lastReported + MIN_STEP_BYTES) {
                return
            }
            lastReported = bytes
            onProgress(bytes)
        }
    }

    private data class DdAttempt(
        val exitCode: Int,
        val timedOut: Boolean,
        val reportedBytes: Long,
        val output: String,
        val statusOptionUnsupported: Boolean,
        val blockSizeUnsupported: Boolean
    ) {
        val success: Boolean get() = exitCode == 0 && !timedOut
    }

    companion object {
        private const val DEVICE_PREFIX = "/dev/block/"
        private const val BLOCK_SIZE_LARGE = "4M"
        private const val BLOCK_SIZE_SAFE = "4096"
        private const val POLL_INTERVAL_MS = 900L
        private const val POLL_JOIN_TIMEOUT_MS = 3000L
        private const val MIN_STEP_BYTES = 512L * 1024L
        private const val TAIL_LIMIT = 4096
        private const val DUMP_TIMEOUT_MS = 12L * 60L * 60L * 1000L
        private val BYTE_RECORD = Regex("(\\d+)\\s+bytes")
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

        fun isUnsupportedOption(text: String, option: String): Boolean {
            val normalized = text.lowercase(Locale.US)
            val optionStem = when {
                option.startsWith("status=") -> "status"
                option.startsWith("bs=") -> "bs="
                else -> option
            }
            val mentionsOption = normalized.contains(option.lowercase(Locale.US)) ||
                normalized.contains(optionStem) ||
                normalized.contains(option.substringAfter('='))
            if (!mentionsOption) return false
            return normalized.contains("invalid") ||
                normalized.contains("unrecognized") ||
                normalized.contains("unknown") ||
                normalized.contains("illegal") ||
                normalized.contains("not supported") ||
                normalized.contains("apenas")
        }
    }
}
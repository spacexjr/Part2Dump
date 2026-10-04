package com.spacexjr.part2dump.checksum

import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.root.Shell
import java.io.File
import java.security.MessageDigest

data class ChecksumOutcome(
    val sha256: String?,
    val source: String,
    val cancelled: Boolean = false,
    val error: String? = null
)

class ChecksumCalculator(private val rootShell: Shell?) {

    fun calculate(file: File, isCancelled: () -> Boolean): ChecksumOutcome {
        if (isCancelled()) return ChecksumOutcome(null, "cancelled", cancelled = true)
        if (!file.exists()) return ChecksumOutcome(null, "none", error = "arquivo ausente")

        if (file.canRead()) {
            val hash = hashInProcess(file, isCancelled)
            if (hash != null) return ChecksumOutcome(hash, "app")
            P2DLog.w("Hash local indisponível para ${file.name}, tentando via root")
        }

        val shell = rootShell
            ?: return ChecksumOutcome(null, "none", error = "sem leitura e sem root")
        return hashViaRoot(shell, file, isCancelled)
    }

    private fun hashInProcess(file: File, isCancelled: () -> Boolean): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var cancelled = false
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) {
                        cancelled = true
                        break
                    }
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            if (cancelled) return null
            toHex(digest.digest())
        } catch (error: Exception) {
            P2DLog.e("Falha ao calcular SHA-256 de ${file.absolutePath}", error)
            null
        }
    }

    private fun hashViaRoot(shell: Shell, file: File, isCancelled: () -> Boolean): ChecksumOutcome {
        val quoted = Shell.quote(file.absolutePath)
        for (command in ROOT_COMMANDS) {
            if (isCancelled()) return ChecksumOutcome(null, "cancelled", cancelled = true)
            val result = try {
                shell.exec("$command $quoted 2>/dev/null", TIMEOUT_MS)
            } catch (error: Exception) {
                P2DLog.w("Comando de hash indisponível: $command")
                continue
            }
            if (result.timedOut) continue
            val hash = result.trimmedStdout().split(Regex("\\s+")).firstOrNull()
            if (hash != null && isValidHash(hash)) {
                return ChecksumOutcome(hash.lowercase(), command)
            }
        }
        return ChecksumOutcome(null, "none", error = "sha256sum indisponível")
    }

    private fun isValidHash(value: String): Boolean = value.length == 64 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    private fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4])
            builder.append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    companion object {
        private const val BUFFER_SIZE = 1 shl 20
        private const val TIMEOUT_MS = 15 * 60_000L
        private const val HEX = "0123456789abcdef"
        private val ROOT_COMMANDS = listOf("sha256sum", "toybox sha256sum", "busybox sha256sum")
    }
}
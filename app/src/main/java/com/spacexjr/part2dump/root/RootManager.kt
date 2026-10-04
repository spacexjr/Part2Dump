package com.spacexjr.part2dump.root

import androidx.annotation.StringRes
import com.spacexjr.part2dump.R
import com.spacexjr.part2dump.core.P2DLog
import java.io.File

class RootManager {

    @Volatile
    private var cached: RootInfo? = null

    @Volatile
    private var cachedShell: Shell? = null

    fun current(): RootInfo? = cached

    fun rootShell(): Shell? = cachedShell?.takeIf { cached?.isAvailable == true }

    fun reader(): Shell = cachedShell ?: Shell.direct()

    fun ensureRoot(@StringRes unavailableRes: Int): RootInfo {
        val existing = cached
        if (existing != null && (existing.isAvailable || existing.status == RootStatus.DENIED)) {
            return existing
        }
        return probe(unavailableRes)
    }

    fun probe(@StringRes unavailableRes: Int): RootInfo {
        P2DLog.i("Verificando acesso root")
        cachedShell = null

        val ordered = orderCandidates()
        var deniedBinary: String? = null
        var deniedDetail: String? = null
        var permissionDenied = false
        var lastError: String? = null

        for (binary in ordered) {
            val shell = Shell.viaSu(binary)
            val result = try {
                shell.exec(ROOT_PROBE_COMMAND, PROBE_TIMEOUT_MS)
            } catch (error: ShellException) {
                P2DLog.w("su indisponível em $binary: ${error.message}")
                if (isPermissionProblem(error)) permissionDenied = true
                lastError = error.message
                continue
            }

            if (result.timedOut) {
                P2DLog.w("Timeout na solicitação de root via $binary")
                cached = RootInfo.error(R.string.error_root_timeout, binary, result.stderr.trim().ifBlank { null })
                return cached!!
            }

            val uid = Shell.uidPattern(result.stdout)
            if (result.exitCode == 0 && uid == 0) {
                P2DLog.i("Root disponível via $binary (uid=$uid)")
                cachedShell = shell
                cached = RootInfo(RootStatus.AVAILABLE, binary, uid, R.string.root_status_available)
                return cached!!
            }

            P2DLog.w("Root negado em $binary: exit=${result.exitCode} out=${result.stdout.trim()} err=${result.stderr.trim()}")
            deniedBinary = binary
            deniedDetail = listOf(result.stderr.trim(), result.stdout.trim())
                .firstOrNull { it.isNotEmpty() }
                ?.ifBlank { null }
        }

        val info = when {
            deniedBinary != null -> RootInfo.denied(R.string.error_root_denied, deniedBinary, deniedDetail)
            permissionDenied -> RootInfo.denied(R.string.error_root_permission, null, lastError)
            lastError != null -> RootInfo.error(R.string.error_root_failed, null, lastError)
            else -> RootInfo.unavailable(unavailableRes, ordered.joinToString().ifBlank { null })
        }
        P2DLog.i("Root indisponível: ${info.status} (${info.detail})")
        cached = info
        return info
    }

    private fun orderCandidates(): List<String> {
        val absolute = Shell.SU_CANDIDATES.filter { it.startsWith("/") && File(it).exists() }
        val bare = Shell.SU_CANDIDATES.filter { !it.startsWith("/") }
        val missing = Shell.SU_CANDIDATES.filter { it.startsWith("/") && !File(it).exists() }
        return (absolute + bare + missing).distinct()
    }

    private fun isPermissionProblem(error: ShellException): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("permission denied") || message.contains("denied")
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 25_000L
        const val ROOT_PROBE_COMMAND = "id -u 2>/dev/null || id 2>/dev/null | head -1"
    }
}
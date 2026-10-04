package com.spacexjr.part2dump.partition

import androidx.annotation.StringRes
import com.spacexjr.part2dump.R
import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.root.Shell
import com.spacexjr.part2dump.root.ShellException

data class ByNameCandidate(
    val directory: String,
    val entryCount: Int,
    val resolvedTarget: String?
)

data class ByNameDetection(
    val directory: String?,
    val candidates: List<ByNameCandidate>,
    @param:StringRes val errorRes: Int? = null
) {
    val isFound: Boolean get() = directory != null

    val resolvedTarget: String?
        get() = candidates.firstOrNull { it.directory == directory }?.resolvedTarget

    val summary: String
        get() = if (isFound) {
            val target = resolvedTarget
            if (!target.isNullOrBlank() && target != directory) "$directory → $target" else directory.orEmpty()
        } else {
            "-"
        }
}

object PartitionPathDetector {

    const val PATH_BY_NAME = "/dev/block/by-name"
    const val PATH_BOOT_DEVICE = "/dev/block/bootdevice/by-name"
    const val PATH_PLATFORM_GLOB = "/dev/block/platform/*/by-name"

    private const val PROBE_TIMEOUT_MS = 10_000L

    fun detect(shell: Shell): ByNameDetection {
        val candidates = mutableListOf<ByNameCandidate>()

        collect(shell, PATH_BY_NAME, candidates)
        collect(shell, PATH_BOOT_DEVICE, candidates)
        expandPlatformDirs(shell).forEach { collect(shell, it, candidates) }

        if (candidates.isEmpty()) {
            P2DLog.w("Nenhum diretório by-name encontrado")
            return ByNameDetection(null, emptyList(), R.string.error_unknown_layout)
        }

        val best = candidates.filter { it.entryCount > 0 }.maxByOrNull { it.entryCount }
        if (best == null) {
            return ByNameDetection(null, candidates, R.string.error_unknown_layout)
        }
        P2DLog.i("Diretório by-name selecionado: ${best.directory} (${best.entryCount})")
        return ByNameDetection(best.directory, candidates)
    }

    private fun expandPlatformDirs(shell: Shell): List<String> = try {
        shell.execLines("ls -1d $PATH_PLATFORM_GLOB 2>/dev/null", PROBE_TIMEOUT_MS)
    } catch (error: ShellException) {
        P2DLog.w("Não foi possível listar plataformas", error)
        emptyList()
    }

    private fun collect(shell: Shell, directory: String, out: MutableList<ByNameCandidate>) {
        if (out.any { it.directory == directory }) return
        val listing = try {
            shell.exec("ls -1 $directory 2>/dev/null", PROBE_TIMEOUT_MS)
        } catch (error: ShellException) {
            P2DLog.w("Falha ao listar $directory: ${error.message}")
            return
        }
        val count = listing.outputLines().size
        if (count == 0) {
            P2DLog.d("Diretório ignorado (vazio ou inacessível): $directory")
            return
        }
        val target = try {
            shell.exec("readlink -f $directory 2>/dev/null", PROBE_TIMEOUT_MS).trimmedStdout()
                .takeIf { it.isNotBlank() }
        } catch (error: ShellException) {
            null
        }
        out.add(ByNameCandidate(directory, count, target))
        P2DLog.i("Candidato: $directory entradas=$count alvo=$target")
    }
}
package com.spacexjr.part2dump.partition

import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.root.Shell
import com.spacexjr.part2dump.root.ShellException

data class ProbedDevice(
    val path: String,
    val sizeBytes: Long,
    val isBlockDevice: Boolean,
    val readable: Boolean
)

object DeviceProbe {

    private const val TIMEOUT_MS = 45_000L

    private val SCRIPT = """
for p in %s; do
  [ -n "${'$'}p" ] || continue
  base=$(basename "${'$'}p")
  sectors=$(cat "/sys/class/block/${'$'}base/size" 2>/dev/null)
  bytes=$(blockdev --getsize64 "${'$'}p" 2>/dev/null)
  blk=0
  [ -b "${'$'}p" ] && blk=1
  readable=0
  if dd if="${'$'}p" bs=4096 count=0 >/dev/null 2>&1; then readable=1; fi
  echo "${'$'}p|${'$'}sectors|${'$'}bytes|${'$'}blk|${'$'}readable"
done
""".trimIndent()

    fun probe(shell: Shell, paths: Collection<String>): Map<String, ProbedDevice> {
        val unique = paths.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return emptyMap()
        val script = String.format(SCRIPT, unique.joinToString(" ") { Shell.quote(it) })
        val output = try {
            shell.exec(script, TIMEOUT_MS).stdout
        } catch (error: ShellException) {
            P2DLog.e("Falha ao consultar dispositivos de bloco", error)
            return emptyMap()
        }
        val result = HashMap<String, ProbedDevice>(unique.size)
        output.lineSequence().forEach { line ->
            val parts = line.trim().split('|')
            if (parts.size < 5) return@forEach
            val path = parts[0].trim()
            if (path.isEmpty()) return@forEach
            val sectors = parts[1].trim().toLongOrNull() ?: -1L
            val bytes = parts[2].trim().toLongOrNull() ?: -1L
            val size = when {
                sectors > 0 -> sectors * 512L
                bytes > 0 -> bytes
                else -> -1L
            }
            result[path] = ProbedDevice(
                path = path,
                sizeBytes = size,
                isBlockDevice = parts[3].trim() == "1",
                readable = parts[4].trim() == "1"
            )
        }
        if (result.size != unique.size) {
            P2DLog.w("Probe parcial: ${result.size} de ${unique.size} dispositivos")
        }
        return result
    }
}
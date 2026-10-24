package ch.nokillswit.telemetry

import ch.nokillswit.infra.catchingFailures
import java.nio.file.Files
import java.nio.file.Path

/** What the container's memory cgroup (and `/proc/self/status`) says; every figure is null when its file is absent or unreadable. */
data class CgroupReading(
    val currentBytes: Long?,
    val anonBytes: Long?,
    /** `current - inactive_file`: the figure the kubelet's eviction compares with the limit. */
    val workingSetBytes: Long?,
    /** The cgroup's LIFETIME peak (`memory.peak`, kernel 5.19+) - not per interval, unlike `mem.old_gen_peak`. */
    val peakBytes: Long?,
    /** Null = no limit (cgroup v2 `max`, or v1's "unlimited" sentinel). */
    val limitBytes: Long?,
    /** `VmRSS` of the process. */
    val rssBytes: Long?,
)

private const val KIB = 1024L

/** cgroup v1 reports "no limit" as a page-rounded `Long.MAX_VALUE`; anything past 2^60 bytes (1 EiB) is that sentinel. */
private const val V1_UNLIMITED_FROM = 1L shl 60

/**
 * Reads the container's memory figures from the cgroup filesystem. The process's OWN cgroup is taken from [procCgroup]
 * (`/proc/self/cgroup`: v2 `0::/path`, v1 the line whose controllers include `memory`) and joined under the mount [root], falling
 * back to the mount root itself - which is the container's cgroup when the runtime gives it a cgroup namespace (the usual case),
 * and the wrong one on a host without one, hence the lookup. v2 (`memory.current`, `memory.stat`, `memory.peak`, `memory.max`) is
 * preferred, v1 (`memory/memory.usage_in_bytes`, ...) is the fallback; a host with neither (macOS, a bare Linux without a memory
 * controller) yields all-null figures except `VmRSS`. A missing or malformed file is a null figure, never an exception: the probe
 * runs on every tick and must not be the reason one fails. All paths are injectable so a test points them at a temp directory.
 */
class CgroupProbe(
    private val root: Path = Path.of("/sys/fs/cgroup"),
    private val procStatus: Path = Path.of("/proc/self/status"),
    private val procCgroup: Path = Path.of("/proc/self/cgroup"),
) {
    fun read(): CgroupReading {
        val rss = rssBytes()
        val own = ownCgroupLines()
        val v2 = listOfNotNull(own.v2?.let { under(root, it) }, root).firstOrNull { Files.exists(it.resolve("memory.current")) }
        return if (v2 != null) readV2(v2, rss) else readV1(v1Dir(own.v1), rss)
    }

    private fun v1Dir(relative: String?): Path {
        val memoryRoot = root.resolve("memory")
        val nested = relative?.let { under(memoryRoot, it) }
        return if (nested != null && Files.exists(nested.resolve("memory.usage_in_bytes"))) nested else memoryRoot
    }

    private fun readV2(dir: Path, rss: Long?): CgroupReading {
        val current = longOf(dir.resolve("memory.current"))
        val stat = stat(dir.resolve("memory.stat"))
        return CgroupReading(
            currentBytes = current,
            anonBytes = stat["anon"],
            workingSetBytes = workingSet(current, stat["inactive_file"]),
            peakBytes = longOf(dir.resolve("memory.peak")),
            // "max" does not parse as a number: no limit.
            limitBytes = longOf(dir.resolve("memory.max")),
            rssBytes = rss,
        )
    }

    private fun readV1(dir: Path, rss: Long?): CgroupReading {
        val current = longOf(dir.resolve("memory.usage_in_bytes"))
        val stat = stat(dir.resolve("memory.stat"))
        return CgroupReading(
            currentBytes = current,
            anonBytes = stat["total_rss"] ?: stat["rss"],
            workingSetBytes = workingSet(current, stat["total_inactive_file"] ?: stat["inactive_file"]),
            peakBytes = longOf(dir.resolve("memory.max_usage_in_bytes")),
            limitBytes = longOf(dir.resolve("memory.limit_in_bytes"))?.takeIf { it < V1_UNLIMITED_FROM },
            rssBytes = rss,
        )
    }

    private class OwnCgroup(val v2: String?, val v1: String?)

    /** `/proc/self/cgroup`: `hierarchy:controllers:path` lines; v2 is `0::path`, v1 lists `memory` among its controllers. */
    private fun ownCgroupLines(): OwnCgroup {
        var v2: String? = null
        var v1: String? = null
        for (line in text(procCgroup)?.lineSequence().orEmpty()) {
            val parts = line.trim().split(':', limit = 3)
            if (parts.size != 3) continue
            when {
                parts[0] == "0" && parts[1].isEmpty() -> v2 = parts[2]
                "memory" in parts[1].split(',') -> v1 = parts[2]
            }
        }
        return OwnCgroup(v2, v1)
    }

    /** [relative] (a cgroup path as `/proc` states it) under [base]; null if it would leave [base] or is not a path. */
    private fun under(base: Path, relative: String): Path? = catchingFailures({
        base.resolve(relative.trimStart('/')).normalize().takeIf { it.startsWith(base.normalize()) }
    }) { null }

    private fun workingSet(current: Long?, inactiveFile: Long?): Long? =
        if (current == null || inactiveFile == null) null else (current - inactiveFile).coerceAtLeast(0)

    private fun rssBytes(): Long? =
        text(procStatus)?.lineSequence()?.firstOrNull { it.startsWith("VmRSS:") }
            ?.removePrefix("VmRSS:")?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()?.let { it * KIB }

    /** `memory.stat`'s `key value` lines. */
    private fun stat(path: Path): Map<String, Long> =
        text(path)?.lineSequence().orEmpty().mapNotNull { line ->
            val parts = line.trim().split(' ')
            val value = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && value != null) parts[0] to value else null
        }.toMap()

    private fun longOf(path: Path): Long? = text(path)?.trim()?.toLongOrNull()

    /** The file's text, or null if it is absent or unreadable (permissions, a controller not mounted, a non-Linux host). */
    private fun text(path: Path): String? = catchingFailures({ if (Files.isRegularFile(path)) Files.readString(path) else null }) { null }
}

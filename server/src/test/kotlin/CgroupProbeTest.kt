package ch.nokillswit

import ch.nokillswit.telemetry.CgroupProbe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `telemetry/CgroupProbe.kt` over temp-directory fixtures: cgroup v2, v1, a host with neither, "max", and garbage - never an exception. */
class CgroupProbeTest {
    private fun tempDir(): Path = Files.createTempDirectory("cgroup-probe")

    private val noProc: Path = Path.of("/nonexistent/proc-self-cgroup")

    private fun procCgroup(text: String): Path = Files.createTempFile("proc-self-cgroup", "").also { it.writeText(text) }

    private fun procStatus(dir: Path, rssKb: Long?): Path {
        val file = dir.resolve("status")
        file.writeText("Name:\tjava\nVmPeak:\t  999 kB\n" + (rssKb?.let { "VmRSS:\t  $it kB\n" } ?: "") + "Threads:\t30\n")
        return file
    }

    @Test
    fun `cgroup v2 figures and the working set`() {
        val root = tempDir()
        root.resolve("memory.current").writeText("300000000\n")
        root.resolve("memory.peak").writeText("350000000\n")
        root.resolve("memory.max").writeText("536870912\n")
        root.resolve("memory.stat").writeText("anon 200000000\nfile 90000000\ninactive_file 40000000\nactive_file 50000000\n")
        val reading = CgroupProbe(root, procStatus(root, 123_456), noProc).read()
        assertEquals(300_000_000L, reading.currentBytes)
        assertEquals(200_000_000L, reading.anonBytes)
        assertEquals(260_000_000L, reading.workingSetBytes, "current - inactive_file")
        assertEquals(350_000_000L, reading.peakBytes)
        assertEquals(536_870_912L, reading.limitBytes)
        assertEquals(123_456L * 1024, reading.rssBytes, "VmRSS is in kB")
    }

    @Test
    fun `v2 max means no limit and a missing memory peak is null`() {
        val root = tempDir()
        root.resolve("memory.current").writeText("100\n")
        root.resolve("memory.max").writeText("max\n")
        root.resolve("memory.stat").writeText("anon 60\ninactive_file 500\n")
        val reading = CgroupProbe(root, root.resolve("absent"), noProc).read()
        assertNull(reading.limitBytes)
        assertNull(reading.peakBytes)
        assertEquals(0L, reading.workingSetBytes, "never negative")
        assertNull(reading.rssBytes)
    }

    @Test
    fun `cgroup v1 figures with the unlimited sentinel read as no limit`() {
        val root = tempDir()
        val memory = root.resolve("memory").also { it.createDirectories() }
        memory.resolve("memory.usage_in_bytes").writeText("400\n")
        memory.resolve("memory.max_usage_in_bytes").writeText("450\n")
        memory.resolve("memory.limit_in_bytes").writeText("1000\n")
        memory.resolve("memory.stat").writeText("rss 250\ntotal_inactive_file 100\ntotal_rss 260\n")
        val limited = CgroupProbe(root, root.resolve("absent"), noProc).read()
        assertEquals(400L, limited.currentBytes)
        assertEquals(260L, limited.anonBytes, "total_rss wins over rss")
        assertEquals(300L, limited.workingSetBytes)
        assertEquals(450L, limited.peakBytes)
        assertEquals(1000L, limited.limitBytes)

        memory.resolve("memory.limit_in_bytes").writeText("9223372036854771712\n")
        assertNull(CgroupProbe(root, root.resolve("absent"), noProc).read().limitBytes)
    }

    @Test
    fun `a host with no cgroup files and no proc yields all nulls`() {
        val reading = CgroupProbe(tempDir().resolve("nowhere"), tempDir().resolve("nothing"), noProc).read()
        assertNull(reading.currentBytes)
        assertNull(reading.anonBytes)
        assertNull(reading.workingSetBytes)
        assertNull(reading.peakBytes)
        assertNull(reading.limitBytes)
        assertNull(reading.rssBytes)
    }

    @Test
    fun `malformed files and directories in place of files are null figures, not failures`() {
        val root = tempDir()
        root.resolve("memory.current").writeText("not a number")
        root.resolve("memory.stat").writeText("anon\nanon x\ninactive_file 1 2 3\n")
        root.resolve("memory.max").createDirectories()
        val proc = tempDir().resolve("status")
        proc.writeText("VmRSS:\tlots kB\n")
        val reading = CgroupProbe(root, proc, noProc).read()
        assertNull(reading.currentBytes)
        assertNull(reading.anonBytes)
        assertNull(reading.workingSetBytes)
        assertNull(reading.limitBytes)
        assertNull(reading.rssBytes)
    }

    @Test
    fun `the process's own nested v2 cgroup is read when the mount root is the host's`() {
        val root = tempDir() // a host mount: the root has no memory.current, the pod's cgroup is a subdirectory
        val own = root.resolve("kubepods.slice/pod1/ctr").also { it.createDirectories() }
        own.resolve("memory.current").writeText("111\n")
        own.resolve("memory.max").writeText("222\n")
        own.resolve("memory.stat").writeText("anon 50\ninactive_file 11\n")
        val other = root.resolve("kubepods.slice/pod2").also { it.createDirectories() }
        other.resolve("memory.current").writeText("999\n")
        val reading = CgroupProbe(root, root.resolve("absent"), procCgroup("0::/kubepods.slice/pod1/ctr\n")).read()
        assertEquals(111L, reading.currentBytes)
        assertEquals(222L, reading.limitBytes)
        assertEquals(100L, reading.workingSetBytes)
    }

    @Test
    fun `a v2 path that does not exist or climbs out of the mount falls back to the root`() {
        val root = tempDir()
        root.resolve("memory.current").writeText("5\n")
        assertEquals(5L, CgroupProbe(root, root.resolve("absent"), procCgroup("0::/gone/away\n")).read().currentBytes)
        assertEquals(5L, CgroupProbe(root, root.resolve("absent"), procCgroup("0::/../../etc\n")).read().currentBytes)
        assertEquals(5L, CgroupProbe(root, root.resolve("absent"), procCgroup("0::/\n")).read().currentBytes, "a cgroup namespace shows /")
        assertEquals(5L, CgroupProbe(root, root.resolve("absent"), procCgroup("garbage\nlines:only\n")).read().currentBytes)
    }

    @Test
    fun `the v1 memory controller line selects the process's own memory cgroup`() {
        val root = tempDir()
        val memory = root.resolve("memory").also { it.createDirectories() }
        memory.resolve("memory.usage_in_bytes").writeText("1\n")
        val own = memory.resolve("kubepods/pod1").also { it.createDirectories() }
        own.resolve("memory.usage_in_bytes").writeText("700\n")
        own.resolve("memory.limit_in_bytes").writeText("2000\n")
        val proc = procCgroup("12:cpu,cpuacct:/kubepods/other\n11:cpuset,memory:/kubepods/pod1\n1:name=systemd:/x\n")
        val reading = CgroupProbe(root, root.resolve("absent"), proc).read()
        assertEquals(700L, reading.currentBytes)
        assertEquals(2000L, reading.limitBytes)
        // No memory line, or a path that is not there: the controller's mount root.
        assertEquals(1L, CgroupProbe(root, root.resolve("absent"), procCgroup("12:cpu:/x\n")).read().currentBytes)
        assertEquals(1L, CgroupProbe(root, root.resolve("absent"), procCgroup("11:memory:/missing\n")).read().currentBytes)
    }

    @Test
    fun `the real probe never throws on this host`() {
        CgroupProbe().read() // Linux CI: real files; macOS: nothing - either way no exception
    }
}

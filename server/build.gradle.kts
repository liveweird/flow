import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult


plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencies {
        constraints {
            // Ktor's Gradle plugin loads these via its Shadow and Jib integrations during the
            // build. Keep the script classpath above the reviewed advisory floors too.
            classpath("com.fasterxml.jackson.core:jackson-core:2.22.3")
            classpath("com.fasterxml.jackson.core:jackson-databind:2.22.3")
            classpath("com.fasterxml.jackson.core:jackson-annotations:2.22")
            classpath("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.3")
            classpath("org.apache.logging.log4j:log4j-api:2.26.1")
            classpath("org.apache.logging.log4j:log4j-core:2.26.1")
            classpath("org.codehaus.plexus:plexus-utils:4.1.0")
        }
    }
}


application {
    mainClass = "io.ktor.server.netty.EngineMain"
    // Footprint tuning for this small, I/O-bound, low-traffic service. Baked into the installDist
    // launcher (bin/server → the Docker image) and `:server:run`; `test` is unaffected. Measured
    // (in Lettuce, the same stack) on a 512 MiB Linux container: baseline G1 drifts ~345→410 MiB
    // RSS as it grows its heap; this config sits at a steady ~270 MiB — ~25% lower and
    // predictable. Startup is ~1.6 s either way; the win is memory, not startup.
    //   - UseSerialGC        : G1's concurrent threads + region metadata are pure overhead for a
    //                          small heap / few cores; SerialGC alone saved ~75 MiB here.
    //   - Xmx256m            : the app holds no large caches; 256 MiB is comfortable headroom for
    //                          light bursts (drop to 192m to trim ~25 MiB more if traffic stays low).
    //   - TieredStopAtLevel=1: C1-only JIT — trims code-cache + C2-compiler memory (~50 MiB here).
    //                          Peak CPU-bound throughput is lower, which is irrelevant for an
    //                          I/O-bound tool; REMOVE this flag if the service ever runs hot.
    //   - ExitOnOutOfMemoryError: a heap OOM used to kill single threads (the worker coroutine, a Netty
    //                          event loop) and leave a half-dead process that compose/k8s never restarted;
    //                          exiting lets `restart: unless-stopped`/k8s restart it and the job lease be reclaimed.
    //                          It fires only on VM-raised OOMs (heap, metaspace, native thread), not on Netty's
    //                          `OutOfDirectMemoryError` ("Direct buffer memory"), which Java code throws.
    //   The next six are the 2026-10-22 heap/thread sizing (build-times.md WHY 26; measured on a scale-20 DERIVE pair,
    //   two connections of 24k issues at `-Xmx512m`, 18 visible cores: peak anon 675 -> ~470 MiB, -30 %, same DERIVE time):
    //   - Xmn64m             : a FIXED 64 MiB young generation. SerialGC's default is a third of the COMMITTED heap, so a
    //                          115 MB heap had 4 MB survivors and every DERIVE batch (~450 MB/s of garbage) overflowed into the
    //                          old generation: the heap ratcheted 15 -> 247 MB for ~70-85 MB live, and never came back.
    //   - SurvivorRatio=2    : two survivor spaces of 16 MiB each (eden 32), so the medium-lived batch garbage dies in the
    //                          young generation instead of being promoted (young GCs x2.7 a session, pauses still 1-2 ms).
    //   - MinHeapFreeRatio=10 / MaxHeapFreeRatio=30 : grow the old generation only as far as needed (default 40/70) and let
    //                          a full collection shrink it back past 30 % free. The heap now commits ~350 MB at a pair's peak instead of the 512 MB cap.
    //   - ShrinkHeapInSteps  : off, so a full collection shrinks straight to the target instead of a step at a time. With it
    //                          on, an explicit GC after a burst shrank nothing. The worker runs one when its last job ends
    //                          (ingest/IdleHeapGiveBack.kt): anon after a pair 676 -> ~320 MiB. Never add
    //                          -XX:+DisableExplicitGC: that give-back relies on System.gc().
    //   - ActiveProcessorCount=2 : the JVM would size Netty (2 x cores event loops), reactor and ForkJoin pools from every
    //                          core of the node (k8s sets no CPU limit): 98 -> 38 threads at 18 visible cores. The cost is
    //                          Dispatchers.Default = 2, i.e. at most two bcrypt hashes at a time (login is rare, the rest of
    //                          the work is I/O). Raise it if login latency ever shows queueing.
    //   - Xss512k            : thread stacks 1 MiB -> 512 KiB (stacks 12 -> 4 MB committed). Nothing here recurses deeply
    //                          beyond the libraries' own parsing (kotlinx-serialization JSON/ADF trees, Exposed, Flyway), which
    //                          stays far under 512 KiB; a StackOverflowError would be the signal to revisit.
    // Override per-deployment with the JAVA_OPTS / SERVER_OPTS env vars (the launcher appends both). Every role gets
    // these flags (web, worker, all and `:server:run`); the worker/all roles raise only -Xmx (JAVA_OPTS=-Xmx512m).
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseSerialGC",
        "-Xmx256m",
        "-XX:TieredStopAtLevel=1",
        "-XX:+ExitOnOutOfMemoryError",
        "-Xmn64m",
        "-XX:SurvivorRatio=2",
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=30",
        "-XX:-ShrinkHeapInSteps",
        "-XX:ActiveProcessorCount=2",
        "-Xss512k",
    )
}

kotlin {
    jvmToolchain(21)
}

kover {
    reports {
        filters {
            excludes {
                // @Serializable data classes are wire shapes: kotlinx-serialization synthesizes one branch
                // per optional property (the default-value mask in the generated constructor and
                // serializer) that no test can meaningfully exercise — the reader's ~40 view DTOs alone
                // added a thousand such branches. Behavior never lives in them (services, validators and
                // renderers are plain classes and stay measured; a DTO's companion object stays measured
                // too, since the annotation sits on the class).
                annotatedBy("kotlinx.serialization.Serializable")
            }
        }
        verify {
            rule {
                // Line-coverage floor (actual 94.49% on 2026-09-26, measured with the @Serializable
                // exclusion above, after trimming the suite down to the generic foundation —
                // re-measure with `:server:koverXmlReport` and RAISE, never lower).
                // 2026-09-28, v0.3.0 M1 (norm gaps + metrics config): actual 97.20% → floor 96.
                // 2026-09-30, v0.3.0 + checkup tier A: actual 98.02% → floor 97.
                // 2026-10-06, v0.4.0 + checkup 2: actual 98.68% → floor 98.
                minBound(98)
                // Branch-coverage floor (actual 77.20%, 2026-09-26; 76.19% at v0.3.0 M1, 2026-09-28 —
                // the phase-2 normalizer's defensive branches dominate what's left). NOTE: `check`
                // runs only koverVerify — run `:server:koverXmlReport` for fresh actuals.
                // 2026-09-30, v0.3.0 + checkup tier A: actual 79.82% → floor 79.
                // 2026-10-06, v0.4.0 + checkup 2: actual 82.29% → floor 82.
                minBound(82, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}

// Static analysis (plain rule sets only — no type resolution). Rule tuning lives in
// config/detekt/detekt.yml; the task rides `check`, so `build` gates on it.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
dependencies {
    constraints {
        // Security floor for a vulnerable runtime transitive (scram-client, via r2dbc-postgresql).
        // Kept as a constraint because server code does not consume its API directly; see the
        // catalog note for provenance.
        implementation(libs.scram.client)
        implementation(libs.scram.common)
    }
    implementation(project(":core"))
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.bodyLimit)
    implementation(ktorLibs.server.cachingHeaders)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    // The Jira Cloud HTTP client (v0.2.0 plan §6): Ktor's client core plus the OkHttp engine, so
    // `infra/outbound/OutboundGuard.kt`'s guarded `okhttp3.OkHttpClient` (Dns pinning, no
    // redirects/proxy/connection-failure-retry) preconfigures the transport `jira/JiraHttp.kt` runs on.
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.okhttp)
    implementation(ktorLibs.server.compression)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.csrf)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.hsts)
    implementation(ktorLibs.server.httpRedirect)
    implementation(ktorLibs.server.metrics)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.rateLimit)
    implementation(ktorLibs.server.resources)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.swagger)
    implementation(libs.angus.mail)
    implementation(libs.bcrypt)
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.r2dbc.pool)
    // Reactor alignment — see the `reactor-bom` comment in gradle/libs.versions.toml: the BOM pins
    // reactor-core/-pool/-netty to one release train (they carry different version numbers), so
    // these two declarations carry no version of their own. Guarded by checkDependencyAlignment.
    implementation(platform(libs.reactor.bom))
    implementation(libs.reactor.pool)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.logback.classic)
    implementation(libs.opentelemetry.logbackAppender)
    implementation(libs.postgresql)
    implementation(libs.r2dbc.postgresql)
    // Netty alignment — see the `netty` comment in gradle/libs.versions.toml: the BOM pins every
    // io.netty module to one version, and reactor-netty (versioned by the Reactor BOM above) wins
    // over the driver's older transitive request (Gradle picks the highest). Guarded by
    // checkDependencyAlignment below.
    implementation(platform(libs.netty.bom))
    implementation(libs.reactor.netty.core)
    // kotlin-reflect rides in transitively (Ktor loads the config modules through it) at whatever
    // version Ktor/Exposed were built with; declaring it lets the Kotlin plugin align it with the
    // stdlib. Guarded by checkDependencyAlignment below.
    implementation(kotlin("reflect"))
    // Jackson dataformat/module — see the `jackson` catalog note. The Jackson BOM pins the whole
    // com.fasterxml.jackson family.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    // Test-only: the test HTTP clients (TestEnvironment.kt's jsonClient()/authedClient()) negotiate
    // application/json and application/problem+json bodies.
    testImplementation(ktorLibs.client.contentNegotiation)
    // Test-only: JiraClientTest exercises HttpJiraClient/JiraHttp against a scripted Ktor
    // MockEngine instead of a real socket — no network, no WireMock needed for that suite.
    testImplementation(ktorLibs.client.mock)
    // Test-only: JiraStubServer runs the real sample-data/jira-stub WireMock mappings in-JVM
    // (JiraSyncPipelineTest, DataSourceTestConnectionTest) — the standalone shaded artifact avoids
    // dragging WireMock's own Jackson/Jetty transitives into the family alignment gate below.
    testImplementation(libs.wiremock.standalone)
    // Test-only: the OpenAPI conformance/spec-validation harness (OpenApiConformance.kt,
    // OpenApiSpecTest.kt) parses and validates documentation.yaml against real traffic — see the
    // `swagger-parser`/`swagger-request-validator` catalog notes.
    testImplementation(libs.swagger.parser.v3)
    testImplementation(libs.swagger.request.validator.core)
    testImplementation(libs.testcontainers.postgresql)
}

// Every test-client interaction with /api/ is validated against the OpenAPI spec (see
// OpenApiConformance.kt). `-Dopenapi.conformance=warn|off` relaxes it for drift triage.
tasks.withType<Test> {
    systemProperty("openapi.conformance", System.getProperty("openapi.conformance", "fail"))
    // The repo root, so tests that read repo files (PostgresImagePinTest) do not depend on the test cwd.
    systemProperty("repo.root", rootDir.absolutePath)
}

// The candidate rule of the `test` task (`include(TEST_CLASS_GLOB)`) and of `balancedCandidateOrder`: ONE definition, so
// the planned order can never drift from the set Gradle runs. `TestClassNamingTest` pins the naming rule itself.
val TEST_CLASS_SUFFIX = "Test.class"
val TEST_CLASS_GLOB = "**/*$TEST_CLASS_SUFFIX"

// The default of `-PtestStallMinutes` (the stall watchdog, `TestStallWatchdog` below). Measured 2026-10-21, one full
// `-Pforks=2` local run (199 classes, 3 min): the longest quiet gap in either fork was 13 s (a class's `@BeforeClass`
// fixture warm-up: Postgres container + stub SYNC + DERIVE) and the slowest single test 7 s. A CI runner is ~1.75x
// slower (23 s; `test-fixtures.md` puts the cold warm-up at ~45 s, and `testApplication` itself caps a test at 60 s),
// so 5 minutes is ~20x the local worst gap and >6x the worst CI figure, while 5x under the 25-minute job timeout. Raise
// it only with a measured gap (every executor logs its own longest one when it finishes).
val DEFAULT_TEST_STALL_MINUTES = 5

// The test classes in the order the `Test` task should see them when it runs several forks (`-Pforks=N`, N > 1).
// Why: Gradle's MaxNParallelTestDefinitionProcessor deals candidate classes to the forks ROUND-ROBIN in visit order
// (position p -> fork p % forks), all up front, with no work stealing; an alphabetical visit let one fork draw most
// of the heavy classes and run alone for the second half of the suite (build-times.md WHY 18). So the order we emit
// IS the fork assignment: classes sorted by weight (`test-weights.tsv`, seconds per class) are dealt greedily
// (LPT) to the least loaded fork that still has a free slot, and each fork's queue is then laid out at the
// positions p % forks == fork. The per-fork slot count is capped at its round-robin share, so the interleaving
// reproduces the plan exactly. Ties break by name, so the order is the same on every machine; a class missing from
// the file weighs the average. Returns an EMPTY list when there is nothing to plan yet (no candidate class files: the
// task graph is resolved before `compileTestKotlin` ran, and the files are looked up again when the task executes) and
// null when the weights file is missing or unparseable; either way the caller falls back to the plain, alphabetical
// tree, and only the null case is worth a warning.
fun balancedCandidateOrder(classesDirs: Set<File>, weightsFile: File, forks: Int): List<Pair<File, String>>? {
    val candidates = classesDirs.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(TEST_CLASS_SUFFIX) }
            .map { dir to it.relativeTo(dir).invariantSeparatorsPath }.toList()
    }.sortedBy { it.second }
    if (candidates.isEmpty()) return emptyList()
    val weights = runCatching {
        weightsFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('\t') to it.substringAfter('\t').trim().toDouble() }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val average = weights.values.average()
    fun weightOf(relativePath: String) = weights[relativePath.removeSuffix(".class").replace('/', '.')] ?: average
    val slots = IntArray(forks) { fork -> (candidates.size - fork + forks - 1) / forks }
    val queues = List(forks) { mutableListOf<Pair<File, String>>() }
    val loads = DoubleArray(forks)
    for (candidate in candidates.sortedWith(compareByDescending<Pair<File, String>> { weightOf(it.second) }.thenBy { it.second })) {
        val fork = (0 until forks).filter { queues[it].size < slots[it] }.minBy { loads[it] }
        queues[fork] += candidate
        loads[fork] += weightOf(candidate.second)
    }
    return candidates.indices.map { position -> queues[position % forks][position / forks] }
}

// The test STALL WATCHDOG (`tasks.test` below, `-PtestStallMinutes`). Why: on 2026-10-17 (CI run 39062260538) one test
// failed and the `server` job then printed NOTHING until the 25-minute job timeout cancelled it; a cancelled job
// uploads no `server-reports` artifact (`if: !cancelled()`), so the hang left no evidence. A hung test JVM is not
// something JUnit or Gradle interrupts, so this watches the one signal that always exists: the Test task's listener
// events. Each test JVM is a "Gradle Test Executor N" suite in the descriptor parent chain and every suite/test start
// or finish is progress. An executor that has started, has not finished and has been quiet for the threshold gets a
// thread dump (`jcmd <pid> Thread.print`: the evidence a cancel destroys), logged AND written under
// `build/reports/hung-tests/`, naming the class/test in flight; then its JVM is killed, so the task fails once the
// other forks have finished their queues instead of at the job timeout. The thread is a daemon and also ends once the task has executed (a failed
// task never runs `doLast`, and the Gradle daemon outlives the build). Time is `System.nanoTime`, which does not tick
// while a laptop sleeps, so a closed lid never reads as a stall.
class TestStallWatchdog(
    private val thresholdMinutes: Int,
    private val reportDir: File,
    private val jcmd: File,
    private val workerMarker: String,
    private val log: Logger,
    private val taskFinished: () -> Boolean,
) : TestListener {
    private class Progress {
        @Volatile var lastEventNanos = System.nanoTime()
        @Volatile var inFlight = "starting up"
        @Volatile var longestGapMillis = 0L
        @Volatile var longestGapAt = "-"
    }

    private val executors = ConcurrentHashMap<String, Progress>()
    private val executorPrefix = "Gradle Test Executor "

    private fun executorOf(descriptor: TestDescriptor): String? {
        var node: TestDescriptor? = descriptor
        while (node != null) {
            if (node.name.startsWith(executorPrefix)) return node.name
            node = node.parent
        }
        return null
    }

    // Also records the longest quiet gap per executor, printed when the executor finishes: the evidence behind the
    // default of `-PtestStallMinutes` (re-measure it from this line when the suite's slowest class warm-up changes).
    private fun touch(descriptor: TestDescriptor, inFlight: String) {
        val executor = executorOf(descriptor) ?: return
        val progress = executors.getOrPut(executor) { Progress() }
        val now = System.nanoTime()
        val gap = TimeUnit.NANOSECONDS.toMillis(now - progress.lastEventNanos)
        if (gap > progress.longestGapMillis) {
            progress.longestGapMillis = gap
            progress.longestGapAt = progress.inFlight
        }
        progress.lastEventNanos = now
        progress.inFlight = inFlight
    }

    override fun beforeSuite(suite: TestDescriptor) = touch(suite, "${suite.className ?: suite.name} (class setup, before its first test)")

    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        if (!suite.name.startsWith(executorPrefix)) return touch(suite, "${suite.className ?: suite.name} (between tests, or finishing)")
        executors.remove(suite.name)?.let { progress ->
            log.lifecycle("test watchdog: ${suite.name} longest quiet gap ${progress.longestGapMillis / 1000} s (in flight: ${progress.longestGapAt})")
        }
    }

    override fun beforeTest(test: TestDescriptor) = touch(test, "${test.className}.${test.name}")

    override fun afterTest(test: TestDescriptor, result: TestResult) = touch(test, "${test.className} (after ${test.name})")

    fun start() {
        val thresholdNanos = TimeUnit.MINUTES.toNanos(thresholdMinutes.toLong())
        val tickMillis = (TimeUnit.MINUTES.toMillis(thresholdMinutes.toLong()) / 4).coerceIn(1_000L, 10_000L)
        Thread(
            {
                while (!taskFinished()) {
                    Thread.sleep(tickMillis)
                    executors.entries.toList().forEach { (executor, progress) ->
                        if (System.nanoTime() - progress.lastEventNanos > thresholdNanos && executors.remove(executor, progress)) {
                            onStall(executor, progress)
                        }
                    }
                }
            },
            "test-stall-watchdog",
        ).apply { isDaemon = true }.start()
    }

    // The worker's command line ends in its name ('Gradle Test Executor 2'), and carries the repo-root property every Test
    // task of this build sets, so another checkout's worker (or "Executor 20" when looking for "2") never matches.
    // Fallback when no command line carries the marker (a platform that truncates it): any descendant whose command
    // line still names the executor. A platform that hides command lines entirely finds nothing; the dump file and the
    // log then say so, and the job timeout remains the backstop (killing unidentified JVMs would be worse).
    private fun workerOf(executor: String): List<ProcessHandle> {
        val name = Regex("(?<![\\w])" + Regex.escape(executor) + "(?!\\d)")
        val workers = ProcessHandle.current().descendants().toList()
        fun line(handle: ProcessHandle) = handle.info().commandLine().orElse("")
        return workers.filter { workerMarker in line(it) && name.containsMatchIn(line(it)) }.take(1)
            .ifEmpty { workers.filter { "GradleWorkerMain" in line(it) && name.containsMatchIn(line(it)) } }
    }

    // Output goes to a file, never a pipe read before waitFor: a worker too wedged to answer the attach would block a
    // pipe read forever, and with it this one watchdog thread (no kill, and no other fork watched).
    private fun threadDump(pid: Long): String = try {
        val out = File.createTempFile("jcmd-$pid-", ".txt")
        try {
            val process = ProcessBuilder(jcmd.path, pid.toString(), "Thread.print", "-l")
                .redirectErrorStream(true).redirectOutput(out).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            out.readText() + if (finished) "" else "\n(jcmd did not finish within 30 s)"
        } finally {
            out.delete()
        }
    } catch (failure: java.io.IOException) {
        "(no thread dump: ${failure.message})"
    }

    private fun onStall(executor: String, progress: Progress) {
        val quietSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - progress.lastEventNanos)
        val workers = workerOf(executor)
        val dump = when (workers.size) {
            0 -> "(no thread dump: the worker process of '$executor' was not found; nothing to kill)"
            1 -> threadDump(workers.single().pid())
            else -> "(the worker of '$executor' could not be told apart; dumping and killing all ${workers.size} candidates)\n" +
                workers.joinToString("\n") { threadDump(it.pid()) }
        }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(reportDir, "${executor.replace(' ', '-')}-$stamp.txt")
        val headline = "$executor made no progress for $quietSeconds s (limit $thresholdMinutes min, -PtestStallMinutes); in flight: ${progress.inFlight}"
        reportDir.mkdirs()
        file.writeText("$headline\n\n$dump\n")
        log.error("$headline\nthread dump: ${file.path}\n$dump\nkilling '$executor': the test task fails once the other forks finish, not at the job timeout")
        workers.forEach { it.destroyForcibly() }
    }
}

// The OpenAPI COVERAGE gate: each test JVM's OpenApiCoverage shutdown hook writes its own
// `exercised-<pid>-<uuid>.txt` and re-merges every fork's file into ONE coverage.md + gaps.txt
// (OpenApiCoverageMerge — a pair exercised by ANY fork is covered, so it is fork-safe). gaps.txt lists every
// declared (operation, status) pair no fork exercised — minus the statuses shared plugins produce for every
// route alike (400/401/413/415/429, pinned once each) and the unforceable 500/default. A non-empty file
// fails the task, but only when the WHOLE suite ran (a `--tests` filter legitimately leaves most of the
// spec unexercised). The directory is cleared first so a previous run's per-fork files never leak in.
tasks.test {
    // Parallel JVM forks (`-Pforks=2` in CI; default 1 = the classic single fork). Every fork is its own JVM, so it
    // gets its OWN Testcontainers Postgres and its own `object` fixture singletons — all the shared-database
    // hazards (withSoloAdmins, restoreSeedAccounts, metrics settings, dim_date, worker claims) stay intra-fork.
    // `forkEvery` is deliberately NOT set (one JVM per fork for the whole run). Measured in build-times.md WHY 5.
    // `-Pforks` must be an integer 1..8 (more forks only add a container + stub SYNC each, WHY 5); anything else
    // fails any build that configures the test task, instead of a NumberFormatException or an unbounded fan-out.
    val forks: Int = (findProperty("forks") as String?).let { raw ->
        if (raw == null) {
            1
        } else {
            raw.trim().toIntOrNull()?.takeIf { it in 1..8 }
                ?: throw GradleException("-Pforks must be an integer from 1 to 8, got '$raw'")
        }
    }
    maxParallelForks = forks
    // Candidates are selected by FILE NAME, not by scanning class files for @Test (Gradle's documented pairing: with
    // scanning off, `include` patterns decide). A later change relies on the exact candidate set; the naming rule
    // behind it is pinned by `TestClassNamingTest`.
    isScanForTestClasses = false
    include(TEST_CLASS_GLOB)
    if (forks > 1) {
        // Replace the plain class tree by one component per candidate class, in the planned order (a composite
        // file collection is visited in declaration order and keeps package-relative paths), plus a per-dir
        // catch-all of everything that is not a candidate, so the set is identical to the plain tree. Only the
        // class files are task inputs; refreshing test-weights.tsv never invalidates a test run. The Callable
        // reads project state at resolution time, which is fine while the configuration cache is off (revisit
        // if it is ever enabled). An unusable weights file warns once (no candidates yet does not) and keeps today's alphabetical order.
        val plainDirs = testClassesDirs
        val weightsFile = file("test-weights.tsv")
        var warned = false
        testClassesDirs = files(
            Callable {
                val order = balancedCandidateOrder(plainDirs.files, weightsFile, forks)
                if (order == null || order.isEmpty()) {
                    if (order == null && !warned) {
                        warned = true
                        logger.warn("test fork balancing skipped: ${weightsFile.name} missing or unparseable (run: node scripts/timings/local-times.mjs test-weights); classes run in alphabetical order")
                    }
                    plainDirs
                } else {
                    order.map { (dir, relativePath) -> objects.fileTree().from(dir).include(relativePath) } +
                        plainDirs.files.map { dir -> objects.fileTree().from(dir).exclude(TEST_CLASS_GLOB) }
                }
            },
        )
    }
    // The stall watchdog (class above): `-PtestStallMinutes` is the longest an UNFINISHED test JVM may go without a
    // single suite/test event before it is thread-dumped and killed. An integer 1..60 like `-Pforks` (the floor so a
    // typo cannot kill a healthy run at once, the ceiling so it can never outlast the CI job's own timeout).
    // Default: DEFAULT_TEST_STALL_MINUTES, see its comment for the measurement behind it.
    val stallMinutes: Int = (findProperty("testStallMinutes") as String?).let { raw ->
        if (raw == null) {
            DEFAULT_TEST_STALL_MINUTES
        } else {
            raw.trim().toIntOrNull()?.takeIf { it in 1..60 }
                ?: throw GradleException("-PtestStallMinutes must be an integer from 1 to 60, got '$raw'")
        }
    }
    val hungTestsDir = layout.buildDirectory.dir("reports/hung-tests")
    val repoRootProperty = "-Drepo.root=${rootDir.absolutePath}"
    val taskLogger = logger
    // Installed at execution time: the toolchain's `jcmd` is resolved then (`javaLauncher` is the JVM the workers run
    // on, so its `jcmd` can attach to them), and every run gets a fresh watchdog with no state in the configuration.
    doFirst {
        hungTestsDir.get().asFile.deleteRecursively() // only this run's dumps
        val jcmd = javaLauncher.get().metadata.installationPath.file("bin/jcmd").asFile
        val watchdog = TestStallWatchdog(stallMinutes, hungTestsDir.get().asFile, jcmd, repoRootProperty, taskLogger) { state.executed }
        addTestListener(watchdog)
        watchdog.start()
    }
    val reportDir = layout.buildDirectory.dir("reports/openapi-conformance")
    val gapsFile = layout.buildDirectory.file("reports/openapi-conformance/gaps.txt")
    doFirst {
        reportDir.get().asFile.let { dir -> dir.deleteRecursively(); dir.mkdirs() }
    }
    // `--tests` lands in the start parameter's task arguments (the filter's command-line patterns are
    // internal API); a filtered run is not the whole suite, so the gate stays quiet.
    val filtered = gradle.startParameter.taskRequests.any { request -> "--tests" in request.args }
    doLast {
        if (filtered || filter.includePatterns.isNotEmpty()) return@doLast
        // A whole-suite run that produced NO gaps file means no fork published its coverage (the shutdown hook
        // broke, or nothing exercised the API) — that must fail, never pass as "no gaps".
        val file = gapsFile.get().asFile
        check(file.exists()) { "OpenAPI coverage gate: ${file.path} is missing — no test fork published its coverage" }
        val gaps = file.readLines().filter { it.isNotBlank() }
        check(gaps.isEmpty()) {
            "OpenAPI coverage gate: ${gaps.size} declared (operation, status) pair(s) were never exercised by the suite — " +
                "add a test per declared status, or trim the spec to what the route can answer:\n  " + gaps.joinToString("\n  ")
        }
    }
}

// Fails the build when a dependency FAMILY that must move as one resolves to several versions on
// the server runtime classpath — the mixed Netty 4.1/4.2 set Ktor + reactor-netty produced, the
// OpenTelemetry incubator drifting from the SDK, kotlin-reflect lagging the stdlib, a Jackson 2
// module drifting from the BOM, or a Reactor module drifting from the reactor-bom's release train
// (the catalog notes explain each pin). Docker-free, rides `check` like detekt.
val checkDependencyAlignment = tasks.register("checkDependencyAlignment") {
    group = "verification"
    description = "Asserts one version per aligned dependency family (Reactor: the BOM's versions) on the server runtime classpath."
    val runtimeClasspath = configurations.runtimeClasspath
    doLast {
        val ids = runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
        // The Reactor modules are the exception to one-version-per-family (reactor-core, reactor-pool
        // and reactor-netty legitimately carry different version numbers as ONE release train), so
        // they are checked against the Reactor BOM's own constraints instead — upstream's pairing
        // table. Read off the platform node of the resolution graph, so no extra artifact is fetched
        // and the documented `--write-locks` command records all it needs.
        val bomCoordinates = "io.projectreactor:reactor-bom"
        val bomNode = runtimeClasspath.get().incoming.resolutionResult.allComponents.singleOrNull {
            (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                ?.let { id -> "${id.group}:${id.module}" == bomCoordinates } == true
        } ?: error("$bomCoordinates is not on the runtime classpath — is the Reactor check still needed?")
        val managed = bomNode.dependencies
            .filter { it.isConstraint }
            .mapNotNull { it.requested as? org.gradle.api.artifacts.component.ModuleComponentSelector }
            .associate { "${it.group}:${it.module}" to it.version }
        val reactorIds = ids.filter { it.group.startsWith("io.projectreactor") }
        check(reactorIds.isNotEmpty()) { "No io.projectreactor module on the runtime classpath — is the Reactor check still needed?" }
        val reactorDrift = reactorIds.mapNotNull { id ->
            when (val declared = managed["${id.group}:${id.name}"]) {
                null -> "${id.group}:${id.name}:${id.version} is not in reactor-bom"
                id.version -> null
                else -> "${id.group}:${id.name} resolved ${id.version}, reactor-bom declares $declared"
            }
        }
        if (reactorDrift.isEmpty()) {
            logger.lifecycle(
                "io.projectreactor aligned with reactor-bom ${libs.versions.reactor.bom.get()} (" +
                    reactorIds.joinToString { "${it.name} ${it.version}" } + ")",
            )
        }
        // family label -> (member predicate, version normalizer)
        val families = mapOf(
            "io.netty" to Pair({ g: String, _: String -> g == "io.netty" }, { v: String -> v }),
            // The alpha/incubator artifacts carry a "-alpha" suffix on the same version number.
            "io.opentelemetry" to Pair({ g: String, _: String -> g == "io.opentelemetry" }, { v: String -> v.removeSuffix("-alpha") }),
            // stdlib-jdk7/jdk8 are empty relocation jars since Kotlin 1.8 — only these two matter.
            "kotlin stdlib/reflect" to Pair(
                { g: String, n: String -> g == "org.jetbrains.kotlin" && (n == "kotlin-stdlib" || n == "kotlin-reflect") },
                { v: String -> v },
            ),
            // The test-only swagger-parser/swagger-request-validator stack rides Jackson 2 too; the
            // BOM keeps every module together. Since 2.22 jackson-annotations is versioned by MINOR
            // only ("2.22" beside "2.22.1"), so the family compares major.minor — a patch drift
            // inside one minor is the BOM's business.
            "com.fasterxml.jackson" to Pair(
                { g: String, _: String -> g.startsWith("com.fasterxml.jackson") },
                { v: String -> v.split('.').take(2).joinToString(".") },
            ),
        )
        val drift = families.mapNotNull { (label, spec) ->
            val (member, normalize) = spec
            val versions = ids.filter { member(it.group, it.name) }.groupBy({ normalize(it.version) }, { it.name })
            if (versions.size == 1) {
                logger.lifecycle("$label aligned at ${versions.keys.single()} (${versions.values.single().size} modules)")
                null
            } else {
                "$label: " + versions.entries.joinToString("; ") { (v, names) -> "$v -> ${names.sorted()}" }
            }
        }
        check(drift.isEmpty() && reactorDrift.isEmpty()) {
            "Dependency families must resolve to ONE version each on the runtime classpath, and Reactor modules " +
                "to the reactor-bom's versions — " + (drift + reactorDrift).joinToString(" | ")
        }
        // Jackson 3 (tools.jackson, a different Java package from com.fasterxml.jackson) rides in
        // through Flyway 13 and coexists with the Jackson 2 line the validators use — the two never
        // share a type. Logged, not failed: what matters is that OUR libraries stay on ONE of them
        // (networknt 3.x would put JsonNode-incompatible trees next to swagger-parser's — see the
        // catalog note), which the family check above guards.
        val jackson3 = ids.filter { it.group.startsWith("tools.jackson") }.map { "${it.name}:${it.version}" }.sorted()
        if (jackson3.isNotEmpty()) logger.lifecycle("tools.jackson (Jackson 3, via Flyway) present alongside Jackson 2: $jackson3")
    }
}
tasks.named("check") { dependsOn(checkDependencyAlignment) }

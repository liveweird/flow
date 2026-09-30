
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
    // Override per-deployment with the JAVA_OPTS / SERVER_OPTS env vars (the launcher appends both).
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseSerialGC",
        "-Xmx256m",
        "-XX:TieredStopAtLevel=1",
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
                minBound(97)
                // Branch-coverage floor (actual 77.20%, 2026-09-26; 76.19% at v0.3.0 M1, 2026-09-28 —
                // the phase-2 normalizer's defensive branches dominate what's left). NOTE: `check`
                // runs only koverVerify — run `:server:koverXmlReport` for fresh actuals.
                // 2026-09-30, v0.3.0 + checkup tier A: actual 79.82% → floor 79.
                minBound(79, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
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

// The OpenAPI COVERAGE gate: each test JVM's OpenApiCoverage shutdown hook writes its own
// `exercised-<pid>-<uuid>.txt` and re-merges every fork's file into ONE coverage.md + gaps.txt
// (OpenApiCoverageMerge — a pair exercised by ANY fork is covered, so it is fork-safe). gaps.txt lists every
// declared (operation, status) pair no fork exercised — minus the statuses shared plugins produce for every
// route alike (400/401/413/415/429, pinned once each) and the unforceable 500/default. A non-empty file
// fails the task, but only when the WHOLE suite ran (a `--tests` filter legitimately leaves most of the
// spec unexercised). The directory is cleared first so a previous run's per-fork files never leak in.
tasks.test {
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

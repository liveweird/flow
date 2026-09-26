
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencies {
        constraints {
            // Keep the root plugin classpath above the FreeMarker path-traversal advisory floor.
            classpath("org.freemarker:freemarker:2.3.35")
        }
    }
}

// Keep resolved build, test, and runtime modules reproducible and visible to the
// vulnerability scanner. Refresh with `./gradlew dependencies --write-locks` for each project.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode = org.gradle.api.artifacts.dsl.LockMode.STRICT
    }
}

subprojects {
    group = "ch.nokillswit"
    version = "1.0.0-SNAPSHOT"
}

// The settings plugins DSL resolves before project buildscript locking is available. Resolve
// the same marker here using the shared version property so its implementation enters the
// root lockfile and the vulnerability scan without adding it to the application classpath.
val settingsPluginAudit = configurations.create("settingsPluginAudit") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    kover(project(":server"))
    add(
        settingsPluginAudit.name,
        "org.gradle.toolchains.foojay-resolver-convention:org.gradle.toolchains.foojay-resolver-convention.gradle.plugin:${providers.gradleProperty("foojayResolverVersion").get()}",
    )
}

tasks.register("verifySettingsPluginAudit") {
    group = "verification"
    description = "Resolves the locked settings plugin graph before vulnerability scanning."
    doLast {
        settingsPluginAudit.resolve()
    }
}

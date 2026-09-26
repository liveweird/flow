
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.detekt)
}

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

// Static analysis, same config as :server. Detekt's default source roots are the plain-JVM
// src/main|test/kotlin, so the KMP source set is listed explicitly.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/commonMain/kotlin")
}


kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // Pins every io.opentelemetry artifact (alpha/incubator included) to the pair the
            // instrumentation line was built for — see the `opentelemetry` note in the catalog.
            api(project.dependencies.platform(libs.opentelemetry.instrumentationBomAlpha))
            api(libs.opentelemetry.exporterLogging)
            api(libs.opentelemetry.exporterOtlp)
            api(libs.opentelemetry.ktorInstrumentation)
            api(libs.opentelemetry.sdkAutoconfigure)
            api(libs.opentelemetry.semconv)
        }

        commonTest.dependencies {
            kotlin("test")
        }
    }
}

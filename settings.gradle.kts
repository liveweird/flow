rootProject.name = "flow"

pluginManagement {
    val foojayResolverVersion = providers.gradleProperty("foojayResolverVersion").get()
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version foojayResolverVersion
    }
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://plugins.gradle.org/m2")
            content {
                includeGroup("org.gradle.toolchains.foojay-resolver-convention")
                includeGroup("org.gradle.toolchains")
            }
        }
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.6.0")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}
include(":core")
include(":server")

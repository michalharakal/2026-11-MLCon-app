rootProject.name = "MoonshineAnywhere"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The Moonshine cartridge (Kotlin API + build recipe, no weights) comes from a checkout of
// SKaiNET-cartridge-blueprints as an included build, exactly as in HomeEvenSmarter next door. Override the location
// with -PblueprintsDir=<path> or a `blueprintsDir` entry in ~/.gradle/gradle.properties.
val blueprintsDir = providers.gradleProperty("blueprintsDir").getOrElse("../../SKaiNET-cartridge-blueprints")
require(file(blueprintsDir).isDirectory) {
    "SKaiNET-cartridge-blueprints checkout not found at $blueprintsDir: pass -PblueprintsDir=<path>"
}
includeBuild(blueprintsDir) {
    dependencySubstitution {
        substitute(module("sk.ainet.cartridge:asr-moonshine-v2-streaming-iree"))
            .using(project(":blueprints:asr-moonshine-v2-streaming-iree"))
    }
}

include(":shared")
include(":androidApp")
include(":desktopApp")

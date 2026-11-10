rootProject.name = "HomeEvenSmarter"

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

// The two cartridge blueprints (Kotlin API + build recipe, no weights) are consumed from a sibling checkout of
// SKaiNET-cartridge-blueprints as an included build. Override the location with -PblueprintsDir=<path> or a
// `blueprintsDir` entry in ~/.gradle/gradle.properties. The checkout is OPTIONAL: without it everything builds
// except the Android app's real-engine adapters — server, desktop app and all tests need nothing from it.
val blueprintsDir = providers.gradleProperty("blueprintsDir").getOrElse("../../SKaiNET-cartridge-blueprints")
if (file(blueprintsDir).isDirectory) {
    includeBuild(blueprintsDir) {
        dependencySubstitution {
            substitute(module("sk.ainet.cartridge:asr-moonshine-v2-streaming-iree"))
                .using(project(":blueprints:asr-moonshine-v2-streaming-iree"))
            substitute(module("sk.ainet.cartridge:nlu-functiongemma-270m-iree"))
                .using(project(":blueprints:nlu-functiongemma-270m-iree"))
        }
    }
} else {
    logger.lifecycle("note: blueprints checkout not found at $blueprintsDir — the Android app's real engines will not resolve (set -PblueprintsDir=<path>); everything else builds normally")
}

include(":core")
include(":cartridges")
include(":app:design")
include(":app:shared")
include(":app:androidApp")
include(":app:desktopApp")
include(":app:screenshots")
include(":server")
include(":tools:distill")

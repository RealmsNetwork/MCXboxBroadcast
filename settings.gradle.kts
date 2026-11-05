include(":core")
include(":bootstrap-geyser")
project(":bootstrap-geyser").projectDir = file("bootstrap/geyser")

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("build-logic")
}

// Prefer the CI workspace checkout, while retaining the sibling checkout path
// used by local development environments.
val geyserForkDir = file("EduGeyser").takeIf { it.isDirectory } ?: file("../EduGeyser")
if (geyserForkDir.isDirectory) {
    includeBuild(geyserForkDir) {
        dependencySubstitution {
            substitute(module("com.github.SendableMetatype.EduGeyser:api")).using(project(":api"))
            substitute(module("com.github.SendableMetatype.EduGeyser:core")).using(project(":core"))
        }
    }
}

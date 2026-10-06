include(":core")
include(":bootstrap-standalone")
include(":bootstrap-geyser")
project(":bootstrap-standalone").projectDir = file("bootstrap/standalone")
project(":bootstrap-geyser").projectDir = file("bootstrap/geyser")

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("build-logic")
}


/*
 * During local development, prefer the checked-out EduGeyser fork over
 * the published dependency so the NethernetManager API and EduFloodgate
 * forwarding changes are compiled against the exact fork in use.
 */
val geyserForkDir = file("../EduGeyser")
if (geyserForkDir.isDirectory) {
    includeBuild(geyserForkDir) {
        dependencySubstitution {
            substitute(module("com.github.SendableMetatype.EduGeyser:api")).using(project(":api"))
            substitute(module("com.github.SendableMetatype.EduGeyser:core")).using(project(":core"))
        }
    }
}

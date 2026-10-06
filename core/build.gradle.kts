plugins {
    id("com.rtm516.mcxboxbroadcast.java-conventions")
}

dependencies {
    api(libs.gson)
    api(libs.java.websocket)
    api(libs.methanol)
    api(libs.minecraftauth)
    api(libs.bundles.protocol)

    compileOnly(libs.nethernet.transport)
    compileOnly(libs.libdatachannel)

    api(libs.sqlite)

    annotationProcessor(libs.configurate.`interface`.ap)
    api(libs.configurate.`interface`)
    implementation(libs.configurate.yaml)
}

sourceSets {
    main {
        blossom {
            val info = GitInfo(indraGit)
            javaSources {
                property("version", info.version)
                property("gitVersion", info.gitVersion)
                property("buildNumber", info.buildNumber.toString())
                property("branch", info.branch)
                property("commit", info.commit)
                property("repository", info.repository)
            }
        }
    }
}

description = "core"

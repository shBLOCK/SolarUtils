import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin.Companion.kotlinNodeJsEnvSpec
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("multiplatform") version "2.1.20"
}

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

kotlin {
    // kotlin multiplatform (jvm + js) setup:
    jvm { }
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.add("-Xnested-type-aliases")
    }

    js {
        binaries.executable()
        browser {
            @OptIn(ExperimentalDistributionDsl::class)
            distribution {
                outputDirectory.set(File("${rootDir}/dist/js"))
            }
            commonWebpackConfig {
                outputFileName = "index.js"
            }
            testTask {
                enabled = false
            }
        }
        compilerOptions {
            target.set("es2015")
        }
    }

    sourceSets {
        val lwjglVersion = "3.3.6"

        // JVM target platforms, you can remove entries from the list in case you want to target
        // only a specific platform
        val targetPlatforms = listOf("natives-windows", "natives-linux", "natives-macos", "natives-macos-arm64")

        @Suppress("unused")
        val commonMain by getting {
            dependencies {
                // add additional kotlin multi-platform dependencies here...

                implementation("kool:kool-core")

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }

        @Suppress("unused")
        val jvmMain by getting {
            dependencies {
                // add additional jvm-specific dependencies here...

                // add required runtime libraries for lwjgl
                for (platform in targetPlatforms) {
                    // lwjgl runtime libs
                    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$platform")
                    listOf("glfw", "opengl", "jemalloc", "nfd", "stb", "vma", "shaderc").forEach { lib ->
                        runtimeOnly("org.lwjgl:lwjgl-$lib:$lwjglVersion:$platform")
                    }
                }
            }
        }

        @Suppress("unused")
        val jsMain by getting {
            dependencies {
                implementation(devNpm("terser", "5.39.0"))
            }
        }
    }
}

task("runnableJar", Jar::class) {
    dependsOn("jvmJar")

    group = "app"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveAppendix.set("runnable")
    manifest {
        attributes["Main-Class"] = "platform.JvmLauncherKt"
    }

    configurations
        .asSequence()
        .filter { it.name.startsWith("common") || it.name.startsWith("jvm") }
        .map { it.copyRecursive().fileCollection { true } }
        .flatten()
        .distinct()
        .filter { it.exists() }
        .map { if (it.isDirectory) it else zipTree(it) }
        .forEach { from(it) }
    from(layout.buildDirectory.files("classes/kotlin/jvm/main"))

    doLast {
        copy {
            from(layout.buildDirectory.file("libs/${archiveBaseName.get()}-runnable.jar"))
            into("${rootDir}/dist/jvm")
        }
    }
}

task("runApp", JavaExec::class) {
    group = "app"
    dependsOn("jvmMainClasses")

    classpath = layout.buildDirectory.files("classes/kotlin/jvm/main")
    configurations
        .filter { it.name.startsWith("common") || it.name.startsWith("jvm") }
        .map { it.copyRecursive().filter { true } }
        .forEach { classpath += it }

    mainClass.set("platform.JvmLauncherKt")
}

@Suppress("unused")
val build by tasks.getting(Task::class) {
    dependsOn("runnableJar")
}

operator fun File.div(relative: String) = resolve(relative)

val KotlinSourceSet.resourcesDir
    get() = resources.srcDirs.also { check(it.size == 1) }.first()!!

val cleanMergedAndDeployedResources by tasks.registering {
    doLast {
        delete("${rootDir}/assets/all")
        delete(kotlin.sourceSets["jvmMain"].resourcesDir / "assets")
        delete(kotlin.sourceSets["jsMain"].resourcesDir / "assets")
    }
}

@Suppress("unused")
val clean by tasks.getting(Task::class) {
    dependsOn(cleanMergedAndDeployedResources)

    doLast {
        delete("${rootDir}/dist")
        delete(fileTree("${rootDir}/wechat/miniprogram/index/src") {
            exclude("README.md")
        })
    }
}

val jsWeChatBuild by tasks.registering {
    group = "wechat"
    dependsOn(tasks["jsBrowserDistribution"])
    doLast {
        delete(fileTree("${rootDir}/wechat/miniprogram/index/src") {
            exclude("README.md")
        })
        copy {
            from(files("${rootDir}/dist/js")) {
                exclude("index.html")
            }
            into("${rootDir}/wechat/miniprogram/index/src")
        }
    }
}

val jsWeChatMinify by tasks.registering(Exec::class) {
    group = "wechat"

    val srcRoot = "${rootDir}/wechat/miniprogram/index/src"

    executable = kotlinNodeJsEnvSpec.executable.get()
    args(
        "${rootDir}/build/js/node_modules/terser/bin/terser",
        "--source-map", "\"url='${srcRoot}/index.min.js.map'\"",
        "--ecma", "2015",
        "--compress", "--mangle",
        "--timings",
        "--output", "${srcRoot}/index.min.js", "${srcRoot}/index.js"
    )

    doLast {
        Files.move(
            file("${srcRoot}/index.min.js").toPath(),
            file("${srcRoot}/index.js").toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        Files.move(
            file("${srcRoot}/index.min.js.map").toPath(),
            file("${srcRoot}/index.js.map").toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

@Suppress("unused")
val jsWeChatMinifiedBuild by tasks.registering {
    group = "wechat"
    dependsOn(jsWeChatBuild)
    dependsOn(jsWeChatMinify)
    jsWeChatMinify.get().mustRunAfter(jsWeChatBuild)
}

val assetsRoot = "${rootDir}/assets"

@Suppress("unused")
val generateAssets by tasks.registering {
    group = "assets"
    doFirst {
        if (!org.gradle.internal.os.OperatingSystem.current().isWindows)
            throw GradleException("processAssets task only works on windows.")
    }

    // clean
    doFirst {
        delete("${rootDir}/assets/generated")
        mkdir("${assetsRoot}/generated")
    }

    // fonts
    doLast {
        mkdir("${assetsRoot}/generated/fonts")

        exec {
            workingDir(assetsRoot)
            executable("${assetsRoot}/msdf-atlas-gen.exe")
            args(
                "-varfont", "NotoSans-VariableFont_wdth,wght.ttf?wght=400",
                "-type", "mtsdf",
                "-size", "36",
                "-chars", "[0x20, 0x7E]",
                "-imageout", "./generated/fonts/NotoSans.png",
                "-json", "./generated/fonts/NotoSans.json"
            )
        }

        run {
            val metaFile = file("${assetsRoot}/generated/fonts/NotoSans.json")
            val gson = GsonBuilder().create()
            val jsonText = metaFile.readText()
            val data = gson.fromJson(jsonText, JsonObject::class.java)
            data.addProperty("name", metaFile.nameWithoutExtension)
            data["atlas"].asJsonObject.remove("distanceRangeMiddle")
            metaFile.writeText(gson.toJson(data))
        }
    }
}

val deployAssets by tasks.registering {
    group = "assets"

    dependsOn(cleanMergedAndDeployedResources)
    mustRunAfter(cleanMergedAndDeployedResources)

    // merge
    doLast {
        copy {
            from("${assetsRoot}/static/")
            into("${assetsRoot}/all/")
        }
        copy {
            from("${assetsRoot}/generated/")
            into("${assetsRoot}/all/")
        }
    }

    // deploy
    doLast {
        copy {
            from("${assetsRoot}/all/")
            into(kotlin.sourceSets["jvmMain"].resourcesDir / "assets")
        }
        copy {
            from("${assetsRoot}/all/")
            into(kotlin.sourceSets["jsMain"].resourcesDir / "assets")
        }
    }
}

@Suppress("unused")
val jvmProcessResources by tasks.getting(Task::class) {
    dependsOn(deployAssets)
}

@Suppress("unused")
val jsProcessResources by tasks.getting(Task::class) {
    dependsOn(deployAssets)
}
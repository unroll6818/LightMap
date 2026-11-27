plugins {
    id("java")
    id("net.fabricmc.fabric-loom") version ("1.18.2")
}

// Same Minecraft, loader and Fabric API versions as the mcbench harness and the Sodium 0.9.3 build
val minecraftVersion = "26.3"
val fabricLoaderVersion = "0.19.3"
val fabricApiVersion = "0.160.5+26.3"
// Sodium's config API, compile-time only: the settings page in its video settings screen.
// Normally taken from Sodium's Maven repository. To compile against a jar on disk instead (any
// Sodium 0.9.3 jar for 26.3 contains the API), pass -PsodiumJar=<path> or set it in
// ~/.gradle/gradle.properties.
val sodiumApiVersion = "0.9.3+mc26.3"
val sodiumJar = providers.gradleProperty("sodiumJar")

group = "dev.lightmap"
version = "0.4.0"

base {
    archivesName = "lightmap"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

repositories {
    if (!sodiumJar.isPresent) {
        maven {
            name = "CaffeineMC"
            url = uri("https://maven.caffeinemc.net/releases")
            content { includeGroup("net.caffeinemc") }
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    // Only the Fabric API modules the mod uses. They are not bundled: the mod expects Fabric API
    // to be installed, as it is in the bench pack.
    for (module in listOf("fabric-api-base", "fabric-lifecycle-events-v1", "fabric-rendering-v1", "fabric-key-mapping-api-v1")) {
        implementation(fabricApi.module(module, fabricApiVersion))
    }

    if (sodiumJar.isPresent) {
        compileOnly(files(sodiumJar.get()))
    } else {
        compileOnly("net.caffeinemc:sodium-fabric-api:$sodiumApiVersion") { isTransitive = false }
    }
}

loom {
    mixin {
        useLegacyMixinAp = false
    }
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to inputs.properties["version"]))
    }
}

// Self-tests: plain main programs with their own tiny assertion helper, so they need no test
// framework. They live in their own source set and are not part of "build"; run them with
//   gradlew selfTest       everything that does not touch the game
//   gradlew gameSelfTest   starts the game's registries (no window, no GPU) and runs the real
//                          capture, colouring, storage and upload code against real game classes
val selfTests: SourceSet = sourceSets.create("selfTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the game-independent self-tests."
    classpath = selfTests.runtimeClasspath
    mainClass.set("dev.lightmap.core.CoreTests")
    // Run on the JDK the classes were compiled for, whatever JDK Gradle itself runs on.
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}

val gameSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the self-tests that need the game's classes (headless)."
    classpath = selfTests.runtimeClasspath
    mainClass.set("dev.lightmap.mc.GameTests")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    jvmArgs("-Djava.awt.headless=true")
    val runDir = layout.buildDirectory.dir("gameSelfTest")
    workingDir(runDir)
    doFirst {
        runDir.get().asFile.mkdirs()
    }
}

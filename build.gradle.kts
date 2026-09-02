plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "com.huidu.farmersdelight"
version = "1.0.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenLocal()
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

// CraftEngine is pinned to the vendored 26.8 jar shipped under libs/.

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")

    // CraftEngine — pinned to the vendored 26.8 jar (26.8-SNAPSHOT is not published to maven).
    compileOnly(files("libs/craft-engine-26.8.jar"))
    compileOnly(files("libs/craft-engine-core-26.8.jar"))
    // CE 26.8 keeps proxy classes in its jar-in-jar proxy artifact.
    compileOnly(files("libs/craft-engine-proxy-26.8.jar"))

    compileOnly("me.clip:placeholderapi:2.11.6")
    // AntiGriefLib: unified protection facade over 24+ land/claim plugins (MIT). Bundled by shadowJar (not
    // relocated — Bukkit plugin classloaders are isolated, so the package cannot clash with another plugin's).
    // isTransitive=false skips its compile-only annotations. Its per-plugin providers load only when the
    // matching land plugin is present, so bundling it adds no runtime coupling to absent plugins.
    implementation("net.momirealms:antigrieflib:1.0.11") { isTransitive = false }
    // bStats metrics (Maven Central). Bundled by shadowJar un-relocated, same as antigrieflib above:
    // Bukkit plugin classloaders are isolated so org.bstats cannot clash with another plugin's copy, and
    // relocating triggers shadow 8.1.7's ASM remap bug. bStats' own relocation self-check is disabled at
    // runtime via System.setProperty("bstats.relocatecheck", "false") before Metrics is constructed.
    implementation("org.bstats:bstats-bukkit:3.1.0")
    // Jackson JSON (Maven Central): parses and merges the vanilla chest loot tables with the FD
    // append pools at datapack install time. Bundled by shadowJar like the other implementation deps.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.3")
    // UltimateAdvancementAPI: separate server plugin; vendored only for offline compile against its API.
    compileOnly(files("libs/UltimateAdvancementAPI-Plugin-2.8.0-folia.jar"))
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(files("libs/craft-engine-26.8.jar"))
    testImplementation(files("libs/craft-engine-core-26.8.jar"))
    testImplementation(files("libs/craft-engine-proxy-26.8.jar"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.all {
    resolutionStrategy {
        // Force patched transitive dependency versions for compilation only; they are not bundled.
        force("org.codehaus.plexus:plexus-utils:4.0.3")
        force("org.apache.commons:commons-lang3:3.18.0")
    }
}

val debugToolsBuild = providers.gradleProperty("debugTools")
    .map { it.equals("true", ignoreCase = true) }
    // Debug tools require -PdebugTools=true. Runtime statistics are available through /fd stats.
    .orElse(false)
val pluginArchiveBaseName = "farmersdelight"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

sourceSets {
    named("main") {
        java.srcDir(layout.buildDirectory.dir("generated/sources/buildFlags"))
        if (debugToolsBuild.get()) {
            java.srcDir("src/debugTools/java")
        }
    }
}

val writeBuildFlags = tasks.register("writeBuildFlags") {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildFlags/com/huidu/farmersdelight")
    inputs.property("debugTools", debugToolsBuild)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("BuildFlags.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.huidu.farmersdelight;

            public final class BuildFlags {

                public static final boolean DEBUG_TOOLS = ${debugToolsBuild.get()};

                private BuildFlags() {
                }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
    }
}

tasks.compileJava {
    dependsOn(writeBuildFlags)
    doFirst {
        if (!debugToolsBuild.get()) {
            delete(layout.buildDirectory.dir("classes/java/main/com/huidu/farmersdelight/debug"))
        }
    }
}

tasks.shadowJar {
    archiveBaseName.set(pluginArchiveBaseName)
    archiveClassifier.set("")
    if (!debugToolsBuild.get()) {
        exclude("com/huidu/farmersdelight/debug/**")
    }
}

tasks.jar {
    enabled = false
}

// api-only jar: just com.huidu.farmersdelight.api.** — for addons to compile against (compileOnly) without
// exposing internal packages. Addons reference only api.**, so this is all they need; the real
// FD plugin provides the implementation at runtime. Output: build/libs/<base>-<version>-api.jar.
tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Builds an api-only jar (com.huidu.farmersdelight.api.**) for addon development."
    dependsOn(tasks.classes)
    archiveClassifier.set("api")
    from(sourceSets.main.get().output) {
        include("com/huidu/farmersdelight/api/**")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

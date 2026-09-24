plugins {
    id("java")
    // Use the Shadow release that supports Gradle 9 and relocates the generated Java classes.
    id("com.gradleup.shadow") version "9.0.0"
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

// CraftEngine is pinned to the official Maven 26.9.1 artifacts.

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    // Already provided by Paper; only the transport API is needed for per-player display packets.
    compileOnly("io.netty:netty-transport:4.1.135.Final")

    // CraftEngine 26.9.1 from the official Maven repository.
    compileOnly("net.momirealms:craft-engine-bukkit:26.9.1")
    compileOnly("net.momirealms:craft-engine-core:26.9.1")
    // CE 26.9.1 keeps proxy classes in its jar-in-jar proxy artifact.
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:26.9.1")

    compileOnly("me.clip:placeholderapi:2.11.6")
    // AntiGriefLib: unified protection facade over 24+ land/claim plugins (MIT). Bundled and relocated:
    // Bukkit plugin classloaders are NOT isolated for legacy plugin.yml plugins (PluginClassLoader falls
    // back to the other plugins' loaders), so an un-relocated copy is shared server-wide and whichever
    // plugin loads first decides the version everyone gets. isTransitive=false skips its compile-only
    // annotations. Its per-plugin providers load only when the matching land plugin is present.
    implementation("net.momirealms:antigrieflib:1.0.11") { isTransitive = false }
    // bStats metrics (Maven Central). Relocated for the same reason, which is also what bStats itself
    // requires of every plugin that bundles it.
    implementation("org.bstats:bstats-bukkit:3.1.0")
    // UltimateAdvancementAPI: separate server plugin; vendored only for offline compile against its API.
    compileOnly(files("libs/UltimateAdvancementAPI-Plugin-2.8.0-folia.jar"))
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("net.momirealms:craft-engine-bukkit:26.9.1")
    testImplementation("net.momirealms:craft-engine-core:26.9.1")
    testImplementation("net.momirealms:craft-engine-bukkit-proxy:26.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.netty:netty-transport:4.1.135.Final")
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
    doLast {
        // Incomplete JUnit reports must not turn a test-listener failure into a successful build.
        val skipped = Regex("(?m)^\\s*<skipped(?:\\s|/|>)")
        val reportsWithSkips = reports.junitXml.outputLocation.get().asFile.walkTopDown()
            .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
            .filter { skipped.containsMatchIn(it.readText()) }.toList()
        check(reportsWithSkips.isEmpty()) {
            "Tests were skipped or not fully reported: ${reportsWithSkips.joinToString { it.name }}"
        }
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
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
    archiveClassifier.set(if (debugToolsBuild.get()) "debug" else "")
    if (!debugToolsBuild.get()) {
        exclude("com/huidu/farmersdelight/debug/**")
    }
    relocate("org.bstats", "com.huidu.farmersdelight.libs.bstats")
    relocate("net.momirealms.antigrieflib", "com.huidu.farmersdelight.libs.antigrieflib")
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

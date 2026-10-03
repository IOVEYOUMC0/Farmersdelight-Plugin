plugins {
    id("java")
    // Use the Shadow release that supports Gradle 9 and relocates the generated Java classes.
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.huidu.farmersdelight"
version = "1.0.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenLocal()
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

// CraftEngine is resolved from Maven. Overridable so a compatibility check can build the same sources
// against another release without editing this file:  gradlew build -PceVersion=26.8.2
val ceVersion = providers.gradleProperty("ceVersion").getOrElse("26.9.1")

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    // Already provided by Paper; only the transport API is needed for per-player display packets.
    compileOnly("io.netty:netty-transport:4.1.135.Final")

    // CraftEngine from the official Maven repository.
    compileOnly("net.momirealms:craft-engine-bukkit:$ceVersion")
    compileOnly("net.momirealms:craft-engine-core:$ceVersion")
    // CE 26.9.1 keeps proxy classes in its jar-in-jar proxy artifact.
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:$ceVersion")

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
    testImplementation("net.momirealms:craft-engine-bukkit:$ceVersion")
    testImplementation("net.momirealms:craft-engine-core:$ceVersion")
    testImplementation("net.momirealms:craft-engine-bukkit-proxy:$ceVersion")
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
    // ApiDocsDriftTest reads the api pages from the wiki repository (checked out beside this repository's
    // parent locally, under wiki/ in CI). Declaring them as inputs keeps the task from staying "up to date"
    // when only a page changed, which is exactly the drift the test exists to catch. Only a checkout that
    // is actually present can be declared: a directory input has to exist.
    listOf(
        file("api-docs"),
        file("wiki/api-docs"),
        file("../../FarmersdelightPluginWiKi/api-docs"),
    ).filter { it.isDirectory }.forEach { docs ->
        inputs.dir(docs).withPropertyName("apiDocs:${docs.name}")
    }
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

val buildFlagsSource = """
    package com.huidu.farmersdelight;

    public final class BuildFlags {

        public static final boolean DEBUG_TOOLS = ${debugToolsBuild.get()};

        private BuildFlags() {
        }
    }
""".trimIndent()

// Generates BuildFlags.java. The main and api compilations each get their own generated directory so
// that the two compilations share no writable path at all (see the api compilation below).
fun registerBuildFlagsTask(taskName: String, generatedRoot: Provider<Directory>): TaskProvider<Task> =
    tasks.register(taskName) {
        val outputDir = generatedRoot.map { it.dir("com/huidu/farmersdelight") }
        inputs.property("debugTools", debugToolsBuild)
        outputs.dir(outputDir)
        doLast {
            val file = outputDir.get().file("BuildFlags.java").asFile
            file.parentFile.mkdirs()
            file.writeText(buildFlagsSource, Charsets.UTF_8)
        }
    }

val writeBuildFlags = registerBuildFlagsTask(
    "writeBuildFlags",
    layout.buildDirectory.dir("generated/sources/buildFlags")
)

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
    // Only the debug build, which never leaves the development machine, carries a classifier.
    archiveClassifier.set(if (debugToolsBuild.get()) "debug" else "")
    if (!debugToolsBuild.get()) {
        exclude("com/huidu/farmersdelight/debug/**")
    }
    manifest {
        attributes(
            "Implementation-Title" to "FarmersDelight",
            "Implementation-Version" to project.version
        )
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
//
// The addons produce this artifact through a Gradle composite build
// (includeBuild("../FarmersDelight") -> :apiJar). That used to run the *main* compilation: :apiJar
// depended on :classes, so an addon build recompiled the main classes into build/classes/java/main and
// Gradle deleted their stale output, while a concurrent FarmersDelight clean/test compile was reading
// them -- which surfaced as a false "100 errors in :compileTestJava". The api compilation below is
// therefore a separate compilation with its own output directory and its own generated BuildFlags
// source, :apiJar depends on it alone, and nothing an addon build runs writes build/classes/java/main.
// The api sources reference internal (non-api) FarmersDelight classes from method bodies, so the api
// compilation compiles the same sources as the main compilation; only its output directory differs.
val apiClassesDir = layout.buildDirectory.dir("classes/java/api")
val writeApiBuildFlags = registerBuildFlagsTask(
    "writeApiBuildFlags",
    layout.buildDirectory.dir("generated/sources/apiBuildFlags")
)

val compileApiJava = tasks.register<JavaCompile>("compileApiJava") {
    group = "build"
    description = "Compiles the api sources into their own output directory, separate from the main compilation."
    // tasks.withType<JavaCompile>().configureEach already covers the source sets' own tasks, but this is
    // a hand-registered compilation, so it has to restate everything those tasks inherit. The toolchain
    // matters most: without it javac runs on whatever JDK the Gradle daemon happens to use, and an older
    // daemon JDK rejects the compilation outright ("invalid target release: 21").
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
    val javaToolchains = project.extensions.getByType<JavaToolchainService>()
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    val sourceRoots = mutableListOf<Any>("src/main/java")
    if (debugToolsBuild.get()) {
        sourceRoots += "src/debugTools/java"
    }
    sourceRoots += layout.buildDirectory.dir("generated/sources/apiBuildFlags")
    source = files(*sourceRoots.toTypedArray()).asFileTree.matching { include("**/*.java") }
    classpath = sourceSets["main"].compileClasspath
    destinationDirectory.set(apiClassesDir)
    dependsOn(writeApiBuildFlags)
}

tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Builds an api-only jar (com.huidu.farmersdelight.api.**) for addon development."
    dependsOn(compileApiJava)
    archiveClassifier.set("api")
    from(apiClassesDir) {
        include("com/huidu/farmersdelight/api/**")
    }
}

// `build` deliberately does not depend on :apiJar: the addons' composite build requests :apiJar
// explicitly, so making every ordinary build compile the sources a second time would only slow the main
// repository down.
tasks.build {
    dependsOn(tasks.shadowJar)
}

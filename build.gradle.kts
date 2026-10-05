import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import java.util.zip.ZipFile

plugins {
    id("java")
    // Use the Shadow release that supports Gradle 9 and relocates the generated Java classes.
    id("com.gradleup.shadow") version "9.0.0"
    // The module is also consumed as a Gradle source dependency (the addons' git channel), and Gradle
    // only offers modules of a source-dependency build that it can find a publication for.
    id("maven-publish")
}

group = "com.huidu.farmersdelight"
version = "1.0.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenLocal()
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    // Snapshots are opt-in only: they are added when the requested ceVersion is a snapshot, so a default
    // build keeps resolving from the release repository alone and stays reproducible. Read straight from the
    // property here because ceVersion itself is defined below this block.
    //   gradlew build -PceVersion=26.9.2-SNAPSHOT
    if (providers.gradleProperty("ceVersion").getOrElse("").endsWith("-SNAPSHOT")) {
        maven("https://repo.momirealms.net/snapshots/")
    }
}

// CraftEngine is resolved from Maven. Overridable so a compatibility check can build the same sources
// against another release without editing this file:  gradlew build -PceVersion=26.8.2
// The default stays on the newest release that exists as a Maven artifact: 26.9.2 is source-only so far
// (only 26.9.2-SNAPSHOT is published, in the snapshots repository), and our code path is the one shared by
// 26.8.2 through 26.9.2. To compile against the snapshot anyway, add the snapshots repository and pass
// -PceVersion=26.9.2-SNAPSHOT, or point -PceJar at a shaded CraftEngine 26.9.2 plugin JAR.
val ceVersion = providers.gradleProperty("ceVersion").getOrElse("26.9.1")

// CraftEngine can also be compiled against a locally supplied plugin JAR. That is the only way to compile
// against a build that was never published to Maven (a 26.10 snapshot, for example, is distributed as a
// shaded plugin JAR only). Both inputs are opt-in; with neither of them set the Maven coordinates below
// are used exactly as before:
//   gradlew build -PceJar=C:\path\to\craft-engine.jar [-PceLibraries=C:\path\to\craft-engine\libraries]
// The plugin JAR carries most of CraftEngine's relocated libraries (net.momirealms.craftengine.libraries.*),
// but not all of them: the 26.10 snapshot keeps its adventure relocation outside the JAR, so a compile
// against it also needs ceLibraries pointing at the extracted libraries directory of that build.
val ceJar = providers.gradleProperty("ceJar")
    .orElse(providers.environmentVariable("FARMERSDELIGHT_CE_JAR"))
val ceLibraries = providers.gradleProperty("ceLibraries")

if (ceJar.isPresent) {
    logger.lifecycle("Compiling CraftEngine against the local JAR ${ceJar.get()}")
}

// CraftEngine keeps its proxy classes in a jar-in-jar entry of the plugin JAR, so that entry has to be
// unpacked before it can go on a compile classpath.
val ceLocalProxy = layout.buildDirectory.file("ceSnapshot/craft-engine-proxy.jar")
val extractCraftEngineProxy = tasks.register("extractCraftEngineProxy") {
    // Inert unless a local CraftEngine JAR is supplied. The input and output stay providers, so nothing
    // is resolved (and no directory is created) while the build is being configured.
    onlyIf { ceJar.isPresent }
    inputs.file(ceJar)
    outputs.file(ceLocalProxy)
    doLast {
        val target = ceLocalProxy.get().asFile
        target.parentFile.mkdirs()
        ZipFile(file(ceJar.get())).use { jar ->
            val entry = jar.getEntry("proxy.jarinjar")
                ?: throw GradleException("${ceJar.get()} has no proxy.jarinjar entry; not a CraftEngine plugin JAR")
            jar.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    // Already provided by Paper; only the transport API is needed for per-player display packets.
    compileOnly("io.netty:netty-transport:4.1.135.Final")

    // CraftEngine: the locally supplied plugin JAR (with its proxy entry and libraries) when -PceJar is
    // set, otherwise the official Maven artifacts.
    if (ceJar.isPresent) {
        compileOnly(files(ceJar.get()))
        compileOnly(files(ceLocalProxy).builtBy(extractCraftEngineProxy))
        if (ceLibraries.isPresent) {
            // Provider-backed so the directory is only scanned when this configuration is resolved.
            compileOnly(ceLibraries.map { dir -> fileTree(dir) { include("**/*.jar") } })
        }
    } else {
        compileOnly("net.momirealms:craft-engine-bukkit:$ceVersion")
        compileOnly("net.momirealms:craft-engine-core:$ceVersion")
        // The proxy classes ship as a separate artifact for every supported version, so it follows ceVersion
        // rather than being pinned: see the version policy in "If you are AI, read me.txt".
        compileOnly("net.momirealms:craft-engine-bukkit-proxy:$ceVersion")
    }

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
    testImplementation("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
    if (ceJar.isPresent) {
        testImplementation(files(ceJar.get()))
        testImplementation(files(ceLocalProxy).builtBy(extractCraftEngineProxy))
        if (ceLibraries.isPresent) {
            testImplementation(ceLibraries.map { dir -> fileTree(dir) { include("**/*.jar") } })
        }
    } else {
        testImplementation("net.momirealms:craft-engine-bukkit:$ceVersion")
        testImplementation("net.momirealms:craft-engine-core:$ceVersion")
        testImplementation("net.momirealms:craft-engine-bukkit-proxy:$ceVersion")
    }
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

// The `api` source set exists so that the addon-facing artifact does not depend on the main compilation
// but is still the very same module: same group/name/version, same single api-only jar. Its compilation
// writes build/classes/java/api, so nothing an addon build runs touches build/classes/java/main.
//
// It compiles the same source roots as `main` because the api sources reference internal (non-api)
// FarmersDelight classes from method bodies, so the api-only jar cannot be produced from the api package
// alone. The two compilations are independent tasks over the same inputs, and each has its own output
// directory and its own generated BuildFlags source.
sourceSets {
    named("main") {
        java.srcDir(layout.buildDirectory.dir("generated/sources/buildFlags"))
        if (debugToolsBuild.get()) {
            java.srcDir("src/debugTools/java")
        }
    }

    create("api") {
        java.setSrcDirs(listOf("src/main/java"))
        resources.setSrcDirs(listOf("src/main/resources"))
        if (debugToolsBuild.get()) {
            java.srcDir("src/debugTools/java")
        }
        java.srcDir(layout.buildDirectory.dir("generated/sources/apiBuildFlags"))
        // Same dependencies as main, but *not* main's classes output: `compileClasspath +=
        // sourceSets["main"].compileClasspath` would drag :classes into the task graph, and an addon
        // build would then recompile the main classes into build/classes/java/main. That is exactly the
        // concurrent-clean hazard this split removes, so the api compilation resolves the external
        // dependencies itself and compiles the shared sources into its own output directory.
        configurations["apiCompileOnly"].extendsFrom(configurations["compileOnly"])
        configurations["apiImplementation"].extendsFrom(configurations["implementation"])
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

// outputDir has to be a task output for the destination directory the api compilation writes into.
val writeApiBuildFlags = registerBuildFlagsTask(
    "writeApiBuildFlags",
    layout.buildDirectory.dir("generated/sources/apiBuildFlags")
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

val compileApiJava = tasks.named<JavaCompile>("compileApiJava") {
    group = "build"
    description = "Compiles the api sources into their own output directory, separate from the main compilation."
    // The api source set inherits the project toolchain, so unlike a hand-registered compilation this
    // task needs no toolchain of its own; only the generated BuildFlags source has to be wired in.
    destinationDirectory.set(apiClassesDir)
    dependsOn(writeApiBuildFlags)
}

tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Builds an api-only jar (com.huidu.farmersdelight.api.**) for addon development."
    dependsOn(compileApiJava)
    archiveClassifier.set("api")
    from(sourceSets["api"].output) {
        include("com/huidu/farmersdelight/api/**")
    }
}

// The module's *main* artifact is the api-only jar under the plain file name, because that is what
// Gradle's source-dependency channel hands over (a classified publication cannot be matched back to the
// producer, and the module identity has to stay the same for both channels).
//
// The built-in `jar` task is disabled instead of re-pointed: the java plugin wires the main source set's
// output into it, so it would require :classes and :compileJava, and an addon build would then recompile
// (and prune) build/classes/java/main while a concurrent FarmersDelight test compile was reading it.
// That is the hazard this split exists to remove, so the main artifact gets its own task that reads only
// the api compilation's output.
tasks.jar {
    enabled = false
}

tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "The module's main artifact: the api-only jar (com.huidu.farmersdelight.api.**)."
    dependsOn(compileApiJava)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveBaseName.set(rootProject.name)
    archiveClassifier.set("")
    from(sourceSets["api"].output) {
        include("com/huidu/farmersdelight/api/**")
    }
}

configurations["apiElements"].outgoing.artifacts.clear()
configurations["apiElements"].outgoing.artifact(tasks.named("pluginJar"))

// Publication that names the module for Gradle's source-dependency channel: it publishes exactly one
// artifact, so the surface an addon gets from git is the same one it gets from the sibling checkout.
// (the<PublishingExtension>() rather than the `publishing { }` accessor: in this script that name
// resolves to the plugin-dependency accessor.)
the<PublishingExtension>().publications {
    register<MavenPublication>("farmersdelightApi") {
        groupId = project.group.toString()
        artifactId = rootProject.name
        version = project.version.toString()
        artifact(tasks.named("pluginJar"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
    // `build` has to leave the addon-facing artifacts behind: the git source-dependency channel resolves
    // this module from a plain checkout and runs `assemble` in it before taking the artifact.
    dependsOn("apiJar", "pluginJar")
}

tasks.assemble {
    dependsOn("apiJar", "pluginJar")
}

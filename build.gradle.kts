plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.7.0")
    }
}

group = "com.huidu.farmersdelight"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenLocal()
    maven("https://repo.momirealms.net/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    compileOnly("net.momirealms:craft-engine-core:26.5")
    compileOnly("net.momirealms:craft-engine-bukkit:26.5")
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:26.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val obfuscateBuild = providers.gradleProperty("obfuscate")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val debugToolsBuild = providers.gradleProperty("debugTools")
    .map { it.equals("true", ignoreCase = true) }
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
        expand("version" to version, "foliaSupported" to false)
    }
    filesMatching("paper-plugin.yml") {
        expand("version" to version, "foliaSupported" to false)
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

val paperResourcesDir = layout.buildDirectory.dir("resources/paper")
val foliaResourcesDir = layout.buildDirectory.dir("resources/folia")

val processPaperResources = tasks.register<ProcessResources>("processPaperResources") {
    group = "build"
    description = "Processes plugin resources for the Paper jar."
    from(sourceSets.main.get().resources)
    into(paperResourcesDir)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to version, "foliaSupported" to false)
    }
    filesMatching("paper-plugin.yml") {
        expand("version" to version, "foliaSupported" to false)
    }
}

val processFoliaResources = tasks.register<ProcessResources>("processFoliaResources") {
    group = "build"
    description = "Processes plugin resources for the Folia jar."
    from(sourceSets.main.get().resources)
    into(foliaResourcesDir)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to version, "foliaSupported" to true)
    }
    filesMatching("paper-plugin.yml") {
        expand("version" to version, "foliaSupported" to true)
    }
}

val paperJar = tasks.register<Jar>("paperJar") {
    group = "build"
    description = "Builds the Paper-compatible plugin jar."
    dependsOn(tasks.named("classes"), processPaperResources)
    archiveBaseName.set(pluginArchiveBaseName)
    archiveClassifier.set("paper")
    from(sourceSets.main.get().output.classesDirs)
    from(paperResourcesDir)
    if (!debugToolsBuild.get()) {
        exclude("com/huidu/farmersdelight/debug/**")
    }
}

val foliaJar = tasks.register<Jar>("foliaJar") {
    group = "build"
    description = "Builds the Folia-compatible plugin jar."
    dependsOn(tasks.named("classes"), processFoliaResources)
    archiveBaseName.set(pluginArchiveBaseName)
    archiveClassifier.set("folia")
    from(sourceSets.main.get().output.classesDirs)
    from(foliaResourcesDir)
    if (!debugToolsBuild.get()) {
        exclude("com/huidu/farmersdelight/debug/**")
    }
}

fun registerObfuscationTask(
    taskName: String,
    taskDescription: String,
    dependency: Any,
    inputJar: Provider<RegularFile>,
    outputFileName: String,
    reportBaseName: String
): TaskProvider<proguard.gradle.ProGuardTask> {
    return tasks.register<proguard.gradle.ProGuardTask>(taskName) {
        group = "build"
        description = taskDescription
        dependsOn(dependency)

        val outputJar = layout.buildDirectory.file("libs/$outputFileName")
        val mappingFile = layout.buildDirectory.file("reports/proguard/$reportBaseName-mapping.txt")
        val configFile = layout.buildDirectory.file("reports/proguard/$reportBaseName-configuration.txt")

        injars(inputJar)
        outjars(outputJar)

        libraryjars("${System.getProperty("java.home")}/jmods/java.base.jmod")
        libraryjars("${System.getProperty("java.home")}/jmods/java.logging.jmod")
        libraryjars("${System.getProperty("java.home")}/jmods/java.desktop.jmod")
        libraryjars(configurations.compileClasspath.get().files)

        keep("""
        public class com.huidu.farmersdelight.FarmersDelightPlugin extends org.bukkit.plugin.java.JavaPlugin {
            public <init>();
            public void onLoad();
            public void onEnable();
            public void onDisable();
        }
    """.trimIndent())
        keepclassmembers("""
        class * {
            @org.bukkit.event.EventHandler <methods>;
        }
    """.trimIndent())
        keepclassmembers("""
        enum * {
            public static **[] values();
            public static ** valueOf(java.lang.String);
            public static final ** *;
        }
    """.trimIndent())
        keep("""
        public class fr.ateastudio.farmersdelight.api.event.** {
            public protected *;
        }
    """.trimIndent())
        keepclassmembers("""
        class com.huidu.farmersdelight.debug.DebugToolsCommand {
            public void execute(org.bukkit.command.CommandSender, java.lang.String, java.lang.String[]);
            public java.util.List tabComplete(org.bukkit.command.CommandSender, java.lang.String[]);
        }
    """.trimIndent())
        keepattributes("RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,Record,PermittedSubclasses,StackMap,StackMapTable")

        optimizationpasses(7)
        dontwarn()
        dontnote()
        allowaccessmodification()
        overloadaggressively()
        repackageclasses("fd")
        adaptclassstrings()
        renamesourcefileattribute("FD")
        printmapping(mappingFile)
        printconfiguration(configFile)
    }
}

val obfuscateJar = registerObfuscationTask(
    taskName = "obfuscateJar",
    taskDescription = "Builds the legacy strongly obfuscated plugin jar.",
    dependency = tasks.shadowJar,
    inputJar = tasks.shadowJar.flatMap { it.archiveFile },
    outputFileName = "$pluginArchiveBaseName-${project.version}-obf.jar",
    reportBaseName = "$pluginArchiveBaseName-${project.version}"
)

val obfuscatePaperJar = registerObfuscationTask(
    taskName = "obfuscatePaperJar",
    taskDescription = "Builds the strongly obfuscated Paper plugin jar.",
    dependency = paperJar,
    inputJar = paperJar.flatMap { it.archiveFile },
    outputFileName = "$pluginArchiveBaseName-${project.version}-paper-obf.jar",
    reportBaseName = "$pluginArchiveBaseName-${project.version}-paper"
)

val obfuscateFoliaJar = registerObfuscationTask(
    taskName = "obfuscateFoliaJar",
    taskDescription = "Builds the strongly obfuscated Folia plugin jar.",
    dependency = foliaJar,
    inputJar = foliaJar.flatMap { it.archiveFile },
    outputFileName = "$pluginArchiveBaseName-${project.version}-folia-obf.jar",
    reportBaseName = "$pluginArchiveBaseName-${project.version}-folia"
)

tasks.register("buildPaper") {
    group = "build"
    description = "Builds the Paper-compatible plugin jar."
    dependsOn(paperJar)
}

tasks.register("buildFolia") {
    group = "build"
    description = "Builds the Folia-compatible plugin jar."
    dependsOn(foliaJar)
}

tasks.register("buildObfuscated") {
    group = "build"
    description = "Builds the strongly obfuscated Paper and Folia plugin jars."
    dependsOn(obfuscatePaperJar, obfuscateFoliaJar)
}

tasks.register("buildAllVariants") {
    group = "build"
    description = "Builds Paper/Folia jars and their strongly obfuscated variants."
    dependsOn(paperJar, foliaJar, obfuscatePaperJar, obfuscateFoliaJar)
}

tasks.build {
    dependsOn(paperJar, foliaJar)
    if (obfuscateBuild.get()) {
        dependsOn(obfuscatePaperJar, obfuscateFoliaJar)
    }
}

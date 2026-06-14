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
    compileOnly("net.momirealms:craft-engine-core:26.5.3")
    compileOnly("net.momirealms:craft-engine-bukkit:26.5.3")
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:26.5.3")
    // UltimateAdvancementAPI: separate server plugin; vendored only for offline compile against its API.
    compileOnly(files("libs/UltimateAdvancementAPI-Plugin-2.8.0-folia.jar"))
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("net.momirealms:craft-engine-core:26.5.3")
    testImplementation("net.momirealms:craft-engine-bukkit:26.5.3")
    testImplementation("net.momirealms:craft-engine-bukkit-proxy:26.5.3")
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
        expand("version" to version)
    }
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
    archiveClassifier.set("")
    if (!debugToolsBuild.get()) {
        exclude("com/huidu/farmersdelight/debug/**")
    }
}

tasks.jar {
    enabled = false
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
        inputs.file(layout.projectDirectory.file("build.gradle.kts"))

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
        keepclassmembers(mapOf("allowobfuscation" to true), """
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
        public class com.huidu.farmersdelight.api.event.** {
            public protected *;
        }
    """.trimIndent())
        keepclassmembers("""
        class com.huidu.farmersdelight.debug.DebugToolsCommand {
            public void execute(org.bukkit.command.CommandSender, java.lang.String, java.lang.String[]);
            public java.util.List tabComplete(org.bukkit.command.CommandSender, java.lang.String[]);
        }
    """.trimIndent())
        keepattributes("SourceFile,LineNumberTable,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,Record,PermittedSubclasses,StackMap,StackMapTable")

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
    taskDescription = "Builds the strongly obfuscated universal plugin jar.",
    dependency = tasks.shadowJar,
    inputJar = tasks.shadowJar.flatMap { it.archiveFile },
    outputFileName = "$pluginArchiveBaseName-${project.version}-obf.jar",
    reportBaseName = "$pluginArchiveBaseName-${project.version}"
)

tasks.register("buildObfuscated") {
    group = "build"
    description = "Builds the strongly obfuscated universal plugin jar."
    dependsOn(obfuscateJar)
}

tasks.build {
    dependsOn(tasks.shadowJar)
    if (obfuscateBuild.get()) {
        dependsOn(obfuscateJar)
    }
}

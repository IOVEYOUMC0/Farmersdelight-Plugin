plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "fr.ateastudio.farmersdelight"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenLocal()
    maven("https://repo.momirealms.net/releases/")
}

dependencies {
    // Baseline compile target. General plugin code is kept compatible with Paper 1.21.4+.
    // TooltipDisplay / DataComponentTypes.TOOLTIP_DISPLAY is not available on this baseline;
    // that specific tooltip-hiding feature requires paper-api 1.21.9-rc1+ (recommended 1.21.10+).
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.momirealms:craft-engine-bukkit:0.0.67")
    compileOnly("net.momirealms:craft-engine-core:0.0.67")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("farmersdelight")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

plugins {
    java
    id("com.gradleup.shadow") version "9.3.0"
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("maven-publish")
}

group = "anon.def9a2a4"
version = "0.1.3"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("me.xiaozhangup.octopus:octopus-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.xiaozhangup:SlimeCargoNext:1.0.2")
    compileOnly(kotlin("stdlib"))
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveBaseName.set("Pipes")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    shadowJar {
        relocate("org.bstats", "anon.def9a2a4.bstats")
        mergeServiceFiles()
        archiveClassifier.set("")
        archiveBaseName.set("Pipes")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "pipes"
            version = project.version.toString()
        }
    }
    repositories {
        mavenLocal()
    }
}

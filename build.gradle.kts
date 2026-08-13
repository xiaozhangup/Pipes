plugins {
    java
    id("me.xiaozhangup.sftp-uploader") version "0.1.0"
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("maven-publish")
}

group = "anon.def9a2a4"
version = "0.1.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
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
    compileOnly("me.xiaozhangup.octopus:octopus-api:26.2-R0.1-SNAPSHOT")
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

sftpUploader {
    host.set("xiaozhangup@s1.dimc.cloud")
    target.set("Minecraft")
    jars.set(listOf(layout.buildDirectory.file("libs/Pipes-0.1.5.jar").get().asFile.absolutePath))
}

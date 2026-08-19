plugins {
    java
}

group = "dev.onistone"
version = "0.1.3"
val extensionVersion = version.toString()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly("org.geysermc.geyser:api:2.11.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("extension.yml") {
        expand("version" to extensionVersion)
    }
}

tasks.jar {
    archiveFileName.set("OniBridge-Geyser.jar")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))
    manifest {
        attributes["Implementation-Title"] = "OniBridge-Geyser"
        attributes["Implementation-Version"] = project.version
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.check {
    dependsOn(tasks.jar)
}

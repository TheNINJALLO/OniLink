plugins {
    id("java")
    id("application")
}

group = "dev.onistone"
version = "0.1.0-SNAPSHOT"

// OneDrive and antivirus scanners can transiently lock Gradle's class directories on Windows.
// CI and local release jobs may place disposable intermediates on a local scratch volume while
// keeping the final standalone jar in OniLink/dist.
providers.environmentVariable("ONILINK_BUILD_DIR").orNull?.let {
    layout.buildDirectory.set(file(it))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.cloudburstmc.protocol:bedrock-codec")
    implementation("org.cloudburstmc.protocol:bedrock-connection")
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.onistone.onilink.OniLink")
}

// Ship the documented config template inside the jar. Only the jar is uploaded to a backend, so
// this file is the ONLY configuration documentation that reaches production — ProxyConfig writes it
// verbatim the first time it starts without a config. Keeping the single copy at the project root
// means the version people read in the repo is byte-for-byte the version they get on the server.
tasks.processResources {
    from("onilink.example.properties")
}

val standaloneJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds a single runnable OniLink jar for distribution."
    archiveFileName.set("OniLink.jar")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = "OniLink"
        attributes["Implementation-Version"] = project.version
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.exists() }
            .map { if (it.isDirectory) it else zipTree(it) }
    })

    exclude(
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.SF"
    )
}

tasks.named("assemble") {
    dependsOn(standaloneJar)
}

tasks.test {
    useJUnitPlatform()

    // Some tests exercise the real login validator, and CloudburstMC's EncryptionUtils fetches Mojang's
    // discovery document over HTTPS in its static initialiser. Tests are forked, so a truststore
    // override on the Gradle JVM does not reach them — forward it explicitly. Needed on machines where
    // TLS is intercepted (Norton does this here); those tests skip themselves when it is absent rather
    // than failing, so nothing depends on this being set.
    // javabridge.viaProxyJar opts into the end-to-end Java join test, which needs a ViaProxy.jar that
    // is too large to vendor. Without it that test skips.
    for (key in listOf(
        "javax.net.ssl.trustStore",
        "javax.net.ssl.trustStorePassword",
        "javabridge.viaProxyJar",
        "proxy.logPackets",
        "proxy.traceBridgeDatagrams",
        "org.slf4j.simpleLogger.defaultLogLevel"
    )) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

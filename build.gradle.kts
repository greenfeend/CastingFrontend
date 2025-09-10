plugins {
    kotlin("jvm") version "1.9.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server and client
    implementation("io.ktor:ktor-server-core-jvm:2.3.3")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.3")
    implementation("io.ktor:ktor-server-html-builder:2.3.3")
    implementation("io.ktor:ktor-client-core-jvm:2.3.3")
    implementation("io.ktor:ktor-client-cio-jvm:2.3.3")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.3")
    // HTML builder for constructing simple pages
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.8.1")
    // JSON serialisation
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    // PostgreSQL and ORM
    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")
    implementation("org.postgresql:postgresql:42.6.0")

    // SLF4J logging implementation
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Test utilities
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.example.castingsystem.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.example.castingsystem.ApplicationKt"
    }
    // This line tells Gradle to just exclude any duplicate files it finds
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
}
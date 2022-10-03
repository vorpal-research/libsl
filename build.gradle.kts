import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.10"
    antlr
    kotlin("plugin.serialization") version "1.5.10"
    `maven-publish`
}

group = "org.jetbrains.research"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    antlr("org.antlr:antlr4:4.11.1")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.2.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "1.8"
    dependsOn("generateGrammarSource")
}

tasks.generateGrammarSource {
    maxHeapSize = "64m"
    outputDirectory = File("${project.buildDir}/generated-src/antlr/main/org/jetbrains/research/libsl")
    arguments = arguments + listOf("-visitor", "-no-listener", "-long-messages")
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.getByName("main").allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "org.jetbrains.research"
            artifactId = "libsl"
            version = "1.0.0"

            from(components["java"])
            artifact(sourcesJar)
        }
    }
}
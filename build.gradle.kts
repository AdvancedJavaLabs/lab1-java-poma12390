plugins {
    kotlin("jvm") version "1.9.20"
    application
    id("io.github.reyerizo.gradle.jcstress") version "0.8.15"
}

group = "org.itmo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
jcstress {
    mode = "default"
    timeMillis = "300"
    forks = "2"
    jcstressDependency = "org.openjdk.jcstress:jcstress-core:0.16"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    minHeapSize = "2g"
    maxHeapSize = "6g"
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt")
}
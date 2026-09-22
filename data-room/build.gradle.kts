plugins {
    kotlin("jvm")
    `java-library`
}

group = "com.readervoice"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":parser"))
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.json:json:20240303")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // 3.3M stress 持久化需要大堆（默认 512MB 不足）
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

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
    // raw_text 存储 benchmark（Option A vs B）用；正式产品走 Room（TASK-040），此依赖仅测试侧
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // ImportPipeline 索引/状态文件（TASK-040 迁 Room 后移除）
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
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

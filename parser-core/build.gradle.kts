plugins {
    kotlin("jvm")
    `java-library`
}

group = "com.readervoice"
version = "0.1.0"

repositories {
    mavenCentral()
}

sourceSets {
    named("main") {
        kotlin.srcDir("../parser/src/main/kotlin")
        kotlin.exclude("com/readervoice/parser/source/ImportPipeline.kt")
        // MOBILE-005 / M1（2026-09-16）：段落包【解除排除】——设备端需要 LogicalParagraph。
        // 依赖闭合已核验：paragraph 只 import source.PhysicalLine 与 java.lang.Character.UnicodeScript
        // （后者 Android API 24+ 原生可用）。ImportPipeline 保持排除（无人引用，JVM 专用）。
        resources.srcDir("../parser/src/main/resources")
    }
}

dependencies {
    // Android provides org.json at runtime; this only supplies JVM compilation symbols.
    compileOnly("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.json:json:20240303")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

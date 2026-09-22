plugins {
    kotlin("jvm")
    `java-library`
}

group = "com.readervoice"
version = "0.1.0"

repositories {
    mavenCentral()
}

// MOBILE-005 / M2: semantic layer as an Android-consumable view of data-room sources.
// Same technique as parser-core: point the source set at the shared sources and exclude the
// JDBC / file-IO files that Android cannot use. Desktop and device then share one implementation.
sourceSets {
    named("main") {
        kotlin.srcDir("../data-room/src/main/kotlin")
        kotlin.exclude("com/readervoice/data/Db.kt")
        kotlin.exclude("com/readervoice/data/Persister.kt")
        kotlin.exclude("com/readervoice/data/RevisionStore.kt")
        kotlin.exclude("com/readervoice/data/CorrectionStore.kt")
        kotlin.exclude("com/readervoice/data/InvalidationStore.kt")
        kotlin.exclude("com/readervoice/data/DatabaseIntegrityVerifier.kt")
        kotlin.exclude("com/readervoice/data/character/CharacterStore.kt")
        kotlin.exclude("com/readervoice/data/character/SchemaMigrationV2.kt")
        kotlin.exclude("com/readervoice/data/character/CharacterCompiler.kt")
        kotlin.exclude("com/readervoice/data/character/LegacyBehaviorAdapter.kt")
        kotlin.exclude("com/readervoice/data/semantic/DatasetExporter.kt")
    }
}

dependencies {
    api(project(":parser-core"))
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

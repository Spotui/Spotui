plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.serialization)
}

// Keep the extractor current: YouTube changes its player JavaScript frequently and
// older signature parsers turn otherwise valid authenticated streams into HTTP 403s.
// Strip Utils so the API-26-compatible patched copy in this module is used.
val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>("stripNewPipeExtractorUtils") {
    archiveFileName.set("NewPipeExtractor-v0.26.5-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(provider { newPipeExtractorRaw.map { zipTree(it) } }) {
        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
    }
}

android {
    namespace = "com.metrolist.innertube"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.brotli)
    implementation(files(newPipeExtractorStripped))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")
    implementation(libs.timber)
    testImplementation(libs.junit)

    coreLibraryDesugaring(libs.desugaring)
}

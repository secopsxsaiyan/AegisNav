import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.aegisnav.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aegisnav.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "2026.03.21"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DOWNLOAD_BASE_URL", "\"https://github.com/secopsxsaiyan/AegisNav/releases/download/data-v1/\"")
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Store native libs uncompressed + page-aligned in APK.
            // Required for Android 16 (API 36) 16KB page size devices (Pixel Fold etc.)
            // Safe on older devices — they ignore alignment, just skip decompression on install.
            useLegacyPackaging = false
            // Exclude native libs that are not 16KB page-aligned and are unnecessary for this app.
            // DataStore shared counter: only needed for multi-process DataStore (we don't use it).
            excludes += setOf("lib/*/libdatastore_shared_counter.so")
            // graphics-path: HW-accelerated path ops, falls back to software when absent.
            excludes += setOf("lib/*/libandroidx.graphics.path.so")
        }
    }
    lint {
        targetSdk = 36
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.5")


    // Maps — MapLibre Native Android 12.3.1 (Vulkan renderer support, upgraded from 11.11.0)
    implementation("org.maplibre.gl:android-sdk-opengl:13.0.1")
    implementation("com.google.android.material:material:1.13.0")

    // GraphHopper routing infrastructure
    // graphhopper-core: graph loading, profiles, CH preparation
    // graphhopper-web-api: GHRequest, GHResponse, Instruction, InstructionList, ResponsePath
    implementation("com.graphhopper:graphhopper-core:6.2") {
        exclude(group = "com.graphhopper", module = "graphhopper-reader-osm")
        exclude(group = "jakarta.xml.bind")
        exclude(group = "jakarta.activation")
        exclude(group = "com.sun.activation")
        // Security: exclude unused transitive deps with known vulns (offline routing only — no HTTP/protobuf/XML)
        exclude(group = "io.netty")
        exclude(group = "com.google.protobuf")
        exclude(group = "com.fasterxml.woodstox")
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.graphhopper:graphhopper-web-api:6.2") {
        exclude(group = "com.graphhopper", module = "graphhopper-reader-osm")
        exclude(group = "jakarta.xml.bind")
        exclude(group = "jakarta.activation")
        exclude(group = "com.sun.activation")
        // Security: exclude unused transitive deps with known vulns (offline routing only — no HTTP/protobuf/XML)
        exclude(group = "io.netty")
        exclude(group = "com.google.protobuf")
        exclude(group = "com.fasterxml.woodstox")
        exclude(group = "org.apache.httpcomponents")
    }

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // SQLCipher - at-rest DB encryption (4.14.0 with native 16KB page alignment support)
    implementation("net.zetetic:sqlcipher-android:4.14.0@aar")

    implementation("androidx.sqlite:sqlite-framework:2.6.2")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.runtime:runtime-livedata:1.7.8")
    implementation("com.google.code.gson:gson:2.13.2")

    // Location — Android native LocationManager (replaces play-services-location + coroutines-play-services)

    // WorkManager (background tile pre-cache)
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // OkHttp (tile download)
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    // zstd decompression for PMTiles (BSD-2-Clause)
    implementation("com.github.luben:zstd-jni:1.5.7-7")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.google.crypto.tink:tink-android:1.20.0")



    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.20")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.5")
}

// ── Security: force-upgrade vulnerable transitive dependencies ────────────────
// GraphHopper 6.2 bundles old jackson/commons versions. Force the build to use
// patched versions. These are transitive deps GH may actually use at runtime.
configurations.all {
    resolutionStrategy {
        // Force-upgrade vulnerable transitive deps to patched versions
        force("com.fasterxml.jackson.core:jackson-core:2.15.3")
        force("com.fasterxml.jackson.core:jackson-databind:2.15.3")
        force("com.fasterxml.jackson.core:jackson-annotations:2.15.3")
        force("commons-io:commons-io:2.18.0")
        force("org.apache.commons:commons-lang3:3.18.0")
        force("org.bouncycastle:bcprov-jdk18on:1.79")
        // Force-upgrade netty (from AGP test tooling com.google.testing.platform) to patched version
        // 4.1.129.Final fixes all known CVEs (SNYK-JAVA-IONETTY-11799531/12485149/12485150/12485151/14423947)
        force("io.netty:netty-codec-http2:4.1.129.Final")
        force("io.netty:netty-codec-http:4.1.129.Final")
        force("io.netty:netty-handler:4.1.129.Final")
        force("io.netty:netty-handler-proxy:4.1.129.Final")
        force("io.netty:netty-common:4.1.129.Final")
        force("io.netty:netty-buffer:4.1.129.Final")
        force("io.netty:netty-transport:4.1.129.Final")
        force("io.netty:netty-transport-native-unix-common:4.1.129.Final")
        force("io.netty:netty-resolver:4.1.129.Final")
        force("io.netty:netty-codec:4.1.129.Final")
        // Force-upgrade protobuf (from AGP test tooling) to patched version
        force("com.google.protobuf:protobuf-java:4.29.3")
        force("com.google.protobuf:protobuf-java-util:4.29.3")
        // Force-upgrade httpclient (from AGP UTP tooling) to patched version
        force("org.apache.httpcomponents:httpclient:4.5.14")
        // Ensure our kotlin-stdlib version wins over old transitive (1.9.0 from AGP test platform)
        force("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.20")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.20")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ── Surveillance data sync ─────────────────────────────────────────────────────
// Manual sync: run `./gradlew syncSurveillanceData` when you want to update camera data.
// NOT wired to automatic builds — Overpass API timeouts can clobber existing data with empty results.
// Set POI_FACTORY_COOKIE env var to enable POI Factory red light + speed cameras.
tasks.register<Exec>("syncSurveillanceData") {
    description = "Download and merge surveillance camera data (OSM/EFF/POI Factory)"
    group       = "aegisnav"
    commandLine("python3", "${rootProject.projectDir}/tools/sync_surveillance_data.py")
    isIgnoreExitValue = true   // don't fail the build if a source is temporarily unreachable
    doFirst {
        println(">> Syncing surveillance data (OSM Overpass / EFF Atlas / POI Factory)...")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
    })
    sourceDirectories.setFrom(files(android.sourceSets.getByName("main").java.directories))
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
}
// ── 16KB page alignment ───────────────────────────────────────────────────────
// AGP 9.x handles 16KB page-aligned packaging natively when useLegacyPackaging=false
// (set above in packaging { jniLibs {} }). No custom zipalign task needed.

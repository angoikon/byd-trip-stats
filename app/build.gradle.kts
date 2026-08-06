import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.byd.tripstats"
    compileSdk = 34

    // Read keystore details from local.properties (gitignored — never commit these)
    val localProps = Properties().also { props ->
        rootProject.file("local.properties").takeIf { it.exists() }
            ?.inputStream()?.use { props.load(it) }
    }

    signingConfigs {
        create("release") {
            storeFile = (System.getenv("KEYSTORE_PATH") 
                ?: localProps.getProperty("storeFile"))
                ?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD") 
                ?: localProps.getProperty("storePassword")
            keyAlias = System.getenv("KEY_ALIAS") 
                ?: localProps.getProperty("keyAlias")
            keyPassword = System.getenv("KEY_PASSWORD") 
                ?: localProps.getProperty("keyPassword")
        }
    }

    // ── Version scheme ────────────────────────────────────────────────────────
    // versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + preRelease
    //   preRelease = 99  → stable release  (versionName = "X.Y.Z")
    //   preRelease = 1–98 → pre-release    (versionName = "X.Y.Z-betaNN")
    // A stable release always has a higher versionCode than any beta of the same
    // version, so beta testers automatically receive the stable upgrade via sideload.
    val versionMajor    = 2
    val versionMinor    = 15
    val versionPatch    = 0
    val versionPre      = 99 // 99 = stable; 1–98 = beta (e.g. 1 → "beta01")
    // Hotfix revision for the SAME versionName. Bumps versionCode ONLY — the in-app
    // updater compares the GitHub tag against versionName (UpdateRepository.isNewerVersion),
    // NOT versionCode, so this does NOT auto-trigger an update, yet it lets us rebuild the
    // same versionName (e.g. to replace a withdrawn build) with a distinguishable versionCode.
    // Keep 0 < rev < 99 (rev 99 would reach the next patch's base and break upgrade ordering).
    // 0 = original; reset to 0 for each new versionName.
    val versionRevision = 0

    val computedVersionCode = versionMajor * 1_000_000 +
                              versionMinor *    10_000 +
                              versionPatch *       100 +
                              versionPre +
                              versionRevision
    val computedVersionName = if (versionPre == 99)
        "$versionMajor.$versionMinor.$versionPatch"
    else
        "$versionMajor.$versionMinor.$versionPatch-beta${versionPre.toString().padStart(2, '0')}"

    defaultConfig {
        applicationId = "com.byd.tripstats"
        minSdk = 29
        targetSdk = 29
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Run instrumented tests as a separate package with its own isolated data
        // directory (/data/data/com.byd.tripstats.test/). Test code cannot touch
        // the real app's DB at /data/data/com.byd.tripstats/ even if reflection fails.
        testApplicationId = "com.byd.tripstats.test"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ── SDK flavor split (DiLink-3 vs DiLink-5) ──────────────────────────────────
    // One APK cannot serve both: bydauto SDK signatures drift (getTotalMileageValue is int on
    // D3, float on D5) and on D5 the classes are NOT on the boot classpath.
    //   dilink3 — current behavior, DEFAULT. Thin bydauto stubs in src/dilink3/ are shadowed at
    //             runtime by the D3 boot-classpath classes (as before).
    //   dilink5 — resolves the REAL DiLink-5 bydauto SDK at runtime via Dilink5SdkInjector
    //             (injects the already-installed com.byd.data.collect apk into this app's own
    //             classloader — no OEM binary ever bundled). D5 code in src/dilink5/.
    // NOTE: flavors rename tasks: assembleRelease -> assembleDilink3Release / assembleDilink5Release.
    flavorDimensions += "sdk"
    productFlavors {
        create("dilink3") {
            dimension = "sdk"
            isDefault = true
        }
        create("dilink5") {
            dimension = "sdk"
        }
    }

    buildTypes {
        debug {
            // Sign debug with the release key when a keystore is configured (env or
            // local.properties) so debug/release stay a single install identity on the
            // dev's car — flipping between them upgrades in place and preserves the DB.
            // Fall back to the default debug keystore when no keystore is present so
            // contributors can build without any local.properties / env setup.
            signingConfig = if (signingConfigs.getByName("release").storeFile != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            buildConfigField("Boolean", "SENSITIVE_DIAGNOSTICS_ENABLED", "true")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("Boolean", "SENSITIVE_DIAGNOSTICS_ENABLED", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    // Custom APK naming: byd-trip-stats-FLAVOR[-release]-VERSION.apk
    // The "-release" token goes ONLY on the dilink3 release APK: legacy (pre-flavor) updaters pick
    // the release asset whose name contains "release", so they deterministically land on D3 — even
    // users who skip a version and jump straight to a dual-flavor release. New clients match their
    // own BuildConfig.FLAVOR (see UpdateRepository).
    applicationVariants.all {
        val flavor = flavorName
        val type = buildType.name
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                val legacyTag = if (flavor == "dilink3" && type == "release") "-release" else ""
                outputFileName = "byd-trip-stats-$flavor$legacyTag-${versionName}.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    // Use `adb install -r` (replace, keep data) instead of uninstall+install.
    // Must be a top-level android {} block — placing this inside defaultConfig {} is
    // invalid DSL and silently ignored, which is why the DB was still getting wiped.
    installation {
        installOptions.add("-r")
    }

    testOptions {
        animationsDisabled = true
    }

    // Room schema JSONs (see ksp { } below) are exported to app/schemas/ and committed.
    // Making them androidTest assets is what lets MigrationTestHelper load an older
    // version and replay the real migrations against it.
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

// Export the Room schema to app/schemas/<db-class>/<version>.json on every build, and COMMIT it.
// Two reasons, both learned the hard way (2026-08):
//   1. Reviewability — a schema change with no version bump shows up as a modification to the
//      EXISTING version's json instead of a new file. That diff is the red flag. Without it,
//      removing an entity field silently changes Room's identity hash and the app only dies
//      later, on an existing install, with "Room cannot verify the data integrity".
//   2. It's the prerequisite for MigrationTestHelper — migrations can only be tested against
//      an exported schema. Note this starts at v12: there are no jsons for v1–v11, so the
//      first testable migration is 12 → 13.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Keep app/src/main/assets/pwa/ in sync with docs/pwa/ so the embedded web server
// serves the same PWA as GitHub Pages.
tasks.register<Copy>("syncPwa") {
    from(rootProject.file("docs/pwa"))
    into(layout.projectDirectory.dir("src/main/assets/pwa"))
}
// Keep app/src/main/assets/changelog.md in sync with the root CHANGELOG.md at every build
tasks.register<Copy>("syncChangelog") {
    from(rootProject.file("CHANGELOG.md"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "changelog.md" }
}
// Keep app/src/main/assets/trip-viewer.html in sync with docs/trip-viewer.html so the
// "Save as HTML" trip export can inject the user's trip JSON into a single self-contained
// HTML file. Copied at build time so the asset always matches the latest viewer.
tasks.register<Copy>("syncTripViewer") {
    from(rootProject.file("docs/trip-viewer.html"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "trip-viewer.html" }
}
tasks.named("preBuild") {
    dependsOn("syncPwa")
    dependsOn("syncChangelog")
    dependsOn("syncTripViewer")
}

// ── DiLink-5 bydauto compile stubs (built from SOURCE, not a committed jar) ──────────
// The dilink5 flavor must compile src/main against the bydauto signatures, but those classes
// must NOT land in the dex (the real OEM SDK is added runtimeOnly). So we compile our hand-written
// signature-only stubs into a compileOnly jar at build time. Sources are tracked (src/dilink3/.../
// bydauto = shared D3 signatures + dilink5-stubsrc/ = the D5 deltas); the jar is a build artifact.
// This lets CI assemble the dilink5 flavor with no gitignored input.
val bydautoStubsClasses = layout.buildDirectory.dir("bydauto-stubs/classes")
val compileBydautoStubs = tasks.register<JavaCompile>("compileBydautoStubs") {
    source(
        fileTree("src/dilink3/java") { include("android/**/*.java") },
        fileTree(rootProject.file("dilink5-stubsrc")) { include("android/**/*.java") },
    )
    // android.* (Context etc.) comes from the platform android.jar on the classpath; --release 17
    // supplies the java.* platform. Signature-only bodies, so no other deps are needed.
    classpath = files(android.bootClasspath)
    options.release.set(17)
    destinationDirectory.set(bydautoStubsClasses)
}
val bydautoStubsJar = tasks.register<Jar>("bydautoStubsJar") {
    dependsOn(compileBydautoStubs)
    from(bydautoStubsClasses)
    archiveFileName.set("bydauto-stubs.jar")
    destinationDirectory.set(layout.buildDirectory.dir("bydauto-stubs"))
}

dependencies {
    // DiLink-5 flavor SDK wiring: COMPILE against the thin bydauto stubs (D3 signatures) so the
    // shared src/main compiles identically to dilink3 (the D3-shaped listener overrides simply
    // won't fire on D5; the real D5 data path is added separately in src/dilink5). Stubs are built
    // from source by the bydautoStubsJar task above — no committed jar. There is no runtimeOnly
    // OEM dependency: the real classes are resolved at RUNTIME by Dilink5SdkInjector, which injects
    // the already-installed com.byd.data.collect apk into the app's own classloader (see
    // BydVehicleDataSource.start()). Nothing OEM is ever bundled into the APK.
    "dilink5CompileOnly"(files(bydautoStubsJar.flatMap { it.archiveFile }))

    // Core Android
    implementation(kotlin("stdlib"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    
    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    
    // DataStore for preferences
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hivemq.mqtt.client)

    // QR code generation (Pro licence request — encodes a pre-filled mailto)
    implementation(libs.zxing.core)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Embedded HTTP server for the web companion PWA
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // osmdroid for offline maps
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    // dadb — JVM ADB client for wireless ADB self-permission setup
    implementation("dev.mobile:dadb:1.2.7")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Window size classes for responsive design
    implementation("androidx.compose.material3:material3-window-size-class:1.3.1")

    // Private-telemetry hooks (RuntimeExtensionHooks / ProLicenseHooks), loaded reflectively at
    // runtime. Maintainer builds from the source module; a trusted contributor without the source
    // can instead drop a binary at app/libs/private-telemetry.jar (telemetry hooks only) and still
    // reproduce release builds. Prefer the source module when present.
    val privateTelemetry = rootProject.findProject(":private-telemetry")
    if (privateTelemetry != null) {
        implementation(privateTelemetry)
    } else {
        file("libs/private-telemetry.jar").takeIf { it.exists() }?.let { implementation(files(it)) }
    }

    // ── Unit tests (src/test) ─────────────────────────────────────────────────
    // JUnit 4 is already present via libs.junit — no change needed there.

    // Coroutines test support — runTest, advanceUntilIdle, advanceTimeBy
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Turbine — concise Flow assertion helper (optional but recommended)
    testImplementation("app.cash.turbine:turbine:1.1.0")

    // ── Instrumented tests (src/androidTest) ─────────────────────────────────
    // Room in-memory database support — required for repository integration tests
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    // Coroutines test support in androidTest
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // AndroidX test core — ApplicationProvider, etc.
    androidTestImplementation("androidx.test:core-ktx:1.5.0")

    // AndroidX test runner (if not already added via BOM)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}

// ── Instrumented-test safety net ────────────────────────────────────────────
// connectedAndroidTest deploys to EVERY attached device and WIPES its app data. Refuse to run if a
// NON-emulator device (e.g. the car at 192.168.x.x:5555) is attached and ANDROID_SERIAL isn't pinned
// to an emulator — otherwise a local run would destroy the car's real trip database.
// Pin the emulator to run:  ANDROID_SERIAL=emulator-5554 ./gradlew connectedDilink3DebugAndroidTest
// CI is unaffected: only an emulator is attached there (and if adb is absent, the check no-ops).
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    doFirst {
        val serial = System.getenv("ANDROID_SERIAL").orEmpty().trim()
        if (serial.startsWith("emulator-")) return@doFirst   // explicitly pinned to an emulator — safe
        val adbOut = runCatching {
            val p = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrDefault("")
        val realDevices = adbOut.lineSequence()
            .drop(1)                                   // skip "List of devices attached"
            .filter { it.trim().endsWith("device") }   // only fully-online devices
            .map { it.substringBefore("\t").substringBefore(" ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("emulator-") }
            .toList()
        if (realDevices.isNotEmpty()) {
            throw GradleException(
                "\n🚨 Refusing to run instrumented tests — non-emulator device(s) attached: $realDevices\n" +
                "   connectedAndroidTest deploys to EVERY device and WIPES its app data (this would\n" +
                "   destroy the car's real trip database).\n" +
                "   → Pin the emulator:  ANDROID_SERIAL=emulator-5554 ./gradlew $name\n" +
                "   → or detach the device:  adb disconnect <serial>\n"
            )
        }
    }
}
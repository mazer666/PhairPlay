/**
 * PhairPlay JVM Test Runner
 *
 * Standalone Gradle project that runs all JVM unit tests WITHOUT the Android
 * Gradle Plugin (AGP). This is necessary in environments where maven.google.com
 * is not accessible (e.g., restricted CI sandboxes).
 *
 * Usage:
 *   cd test-runner && gradle test
 *
 * All dependencies are resolved from Maven Central only.
 *
 * Design: production + test sources are compiled together as one Kotlin module
 * so that `internal` members in production code are accessible from test code
 * without any friend-module workarounds.  The `test` task is configured to
 * scan the main output directory for @Test classes.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin {
            // Production sources + test sources in one compilation unit
            srcDirs(
                "../app/src/main/kotlin",
                "../app/src/firetv/kotlin",
                "../app/src/test/kotlin",
                "src/stubs"               // stub files shadow Android-heavy excluded files
            )
            // Exclude files that depend on AndroidX (DataStore, Leanback, NotificationCompat, R)
            // or the full Android Activity lifecycle
            exclude(
                "**/ui/**",
                "**/MainActivity.kt",
                "**/PhairPlayApp.kt",
                "**/settings/SettingsRepository.kt",
                "**/service/PhairPlayService.kt",
                "**/service/BootReceiver.kt",
                "**/MainActivityTest.kt",
                // MdnsServiceTest mocks NsdManager whose static initializer calls
                // VMRuntime.newUnpaddedArray (JNI) — not available in the JVM.
                // This test must run as an Android instrumented test on a real device.
                "**/MdnsServiceTest.kt",
                // ServiceControllerTest triggers android.os.Build.<clinit> which calls
                // SystemProperties.native_get (JNI) — not available in the JVM.
                "**/ServiceControllerTest.kt",
                // NetworkUtilsTest mocks ContentResolver which triggers android.os.Build
                // via ContentResolver.<clinit> → SystemProperties.native_get (JNI).
                "**/NetworkUtilsTest.kt",
                // VideoDecoder is shadowed by src/stubs/VideoDecoder.kt which has no
                // MediaCodec/Surface dependencies but exposes the companion-object
                // members (parseSpsResolution, SpsBitReader) needed by VideoDecoderSpsTest.
                "**/airplay/VideoDecoder.kt",
                // DLNA glue that references R (launcher icon) — everything else in dlna/ compiles on the JVM.
                "**/dlna/DlnaIcon.kt",
                "**/dlna/DlnaReceiver.kt"
            )
        }
    }
}

dependencies {
    // ── Android API stubs ──────────────────────────────────────────────────────
    // Robolectric's android-all JAR provides all Android class definitions
    // (MediaCodec, AudioTrack, NsdManager, etc.) for compile-time and runtime.
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")

    // ── Kotlin ─────────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")

    // ── Coroutines ─────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // ── Bouncy Castle (AES-128-CTR for AudioPlayer) ────────────────────────────
    // bcprov-jdk15on was renamed to bcprov-jdk18on starting with version 1.71
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ── dd-plist (binary plist encode/decode for the AirPlay 2 handshake) ──────
    // PlistCodec (compiled here from app/src/main) imports com.dd.plist.*; on Maven Central.
    implementation("com.googlecode.plist:dd-plist:1.28")

    // ── Timber 4.7.1 ───────────────────────────────────────────────────────────
    // timber-4.7.1 ships as an AAR on Maven Central; without AGP we can't unpack it.
    // Instead we reference the classes.jar extracted from that AAR directly.
    // Timber without a planted Tree swallows all log calls, so android.util.Log
    // is never reached during unit tests even when it is present as a stub.
    implementation(files("libs/timber-4.7.1-classes.jar"))

    // ── Test dependencies (in main scope because sources are merged) ───────────
    implementation("junit:junit:4.13.2")
    implementation("io.mockk:mockk:1.13.10")
}

tasks.test {
    // The test task must scan the main output because test sources are in main
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = sourceSets.main.get().runtimeClasspath
    useJUnit()
    // Allow dynamic agent loading required by MockK's bytecode instrumentation
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

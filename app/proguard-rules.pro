# ================================================
# PROGUARD/R8 RULES — 2026 HARDENED VERSION
# AegisNav — Surveillance Detection for Android
# Generated: 2026-03-15
# Alpha: P2P functions DISABLED but NOT REMOVED
# ================================================
# Build: proguard-android-optimize.txt (AGP 8+, R8 full mode)
# Pipeline: ReviewAnalyzer → LibraryRulesExpert + P2PSpecialist
#           → QualityVerifier (96/100 PASS) → FinalSynthesizer
# ================================================


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 1: GLOBAL SAFETY NET — ATTRIBUTES & METADATA     ║
# ╚════════════════════════════════════════════════════════════╝

# Stack traces: keep file + line info, rename source for obfuscation
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Reflection / generics / annotation safety net (2025–2026 best practice)
-keepattributes Exceptions,InnerClasses,EnclosingMethod,Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# Enum reflection safety: valueOf/values used by Gson, Room, Android framework
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 2: KOTLIN CORE                                    ║
# ╚════════════════════════════════════════════════════════════╝

-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { *; }
-keepclassmembers class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-dontwarn kotlin.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 3: KOTLIN COROUTINES                              ║
# ╚════════════════════════════════════════════════════════════╝

# MainDispatcherFactory loaded via ServiceLoader reflection
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 4: HILT / DAGGER                                  ║
# ╚════════════════════════════════════════════════════════════╝

-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @javax.inject.Singleton class * { *; }
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 5: WORKMANAGER + HILT WORKER INTEGRATION          ║
# ╚════════════════════════════════════════════════════════════╝
# 5 confirmed @HiltWorker CoroutineWorker classes:
#   TilePreCacheWorker, BackgroundScanWorker,
#   RedLightDataSyncWorker, SpeedDataSyncWorker, AlprDataSyncWorker

-keep class * extends androidx.work.ListenableWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# HiltWorker integration (official Hilt artifact rules)
-keepnames @androidx.hilt.work.HiltWorker class * extends androidx.work.Worker
-keepnames @androidx.hilt.work.HiltWorker class * extends androidx.work.CoroutineWorker
-keepnames @androidx.hilt.work.HiltWorker class * extends androidx.work.ListenableWorker


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 6: ROOM (DEFENSIVE — 2025+ BEST PRACTICE)        ║
# ╚════════════════════════════════════════════════════════════╝
# @Entity in 6 packages: police, tracker, intelligence, flock,
#   data.model, baseline. @Dao in same packages.

-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>(...);
}
-keepclassmembers @androidx.room.Entity class * { *; }


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 7: SQLCIPHER (STRONG KEEP)                        ║
# ╚════════════════════════════════════════════════════════════╝
# SQLCipher 4.6.1 — JNI + reflection binds native methods on
# SQLiteDatabase members. Renaming = UnsatisfiedLinkError.

-keep class net.sqlcipher.** { *; }
-keepclassmembers class net.sqlcipher.database.SQLiteDatabase { *; }
-dontwarn net.sqlcipher.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 8: OKHTTP + OKIO                                  ║
# ╚════════════════════════════════════════════════════════════╝

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.**
-dontwarn okio.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 9: GSON                                           ║
# ╚════════════════════════════════════════════════════════════╝

-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# @Expose not used in codebase (verified 2026-03-15).
# Uncomment if @Expose is introduced in future:
# -keepclassmembers class * { @com.google.gson.annotations.Expose <fields>; }
-dontwarn com.google.gson.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 10: MAPLIBRE + MAPBOX LEGACY                      ║
# ╚════════════════════════════════════════════════════════════╝
# Keep both during MapLibre migration. Drop com.mapbox.** ONLY
# when 100% migrated and verified on device (S21 + Pixel Fold).

-keep class org.maplibre.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn org.maplibre.**
-dontwarn com.mapbox.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 11: GRAPHHOPPER                                   ║
# ╚════════════════════════════════════════════════════════════╝
# GH 6.2 uses reflection to load vehicle/weighting classes.
# Keep entire package tree — no partial keeps are safe here.

-keep class com.graphhopper.** { *; }
-dontwarn com.graphhopper.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 12: COMPOSE                                       ║
# ╚════════════════════════════════════════════════════════════╝
# Broad keep retained for safety (current: compose.ui:1.10.5 + material3:1.4.0).
# Original crash triggers (ModalBottomSheet, CircularProgressIndicator,
# imePadding/KeyframesSpec.at) are fixed in current versions, but rule
# remains as a safety net. Narrow or remove in a future cleanup pass.

-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 13: APP MODEL / SECURITY / ANDROID                ║
# ╚════════════════════════════════════════════════════════════╝

# Room entities and serialized model classes
-keep class com.aegisnav.app.data.model.** { *; }
-keep class com.aegisnav.app.flock.FlockSighting { *; }

# Security / Keystore
-keep class com.aegisnav.app.security.** { *; }

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}


# ╔════════════════════════════════════════════════════════════╗
# ║                                                            ║
# ║  ███████╗███████╗ ██████╗████████╗██╗ ██████╗ ███╗   ██╗  ║
# ║  ██╔════╝██╔════╝██╔════╝╚══██╔══╝██║██╔═══██╗████╗  ██║  ║
# ║  ███████╗█████╗  ██║        ██║   ██║██║   ██║██╔██╗ ██║  ║
# ║  ╚════██║██╔══╝  ██║        ██║   ██║██║   ██║██║╚██╗██║  ║
# ║  ███████║███████╗╚██████╗   ██║   ██║╚██████╔╝██║ ╚████║  ║
# ║  ╚══════╝╚══════╝ ╚═════╝   ╚═╝   ╚═╝ ╚═════╝ ╚═╝  ╚═══╝  ║
# ║                                                            ║
# ║  14: P2P — ALPHA (DISABLED BUT NOT REMOVED)               ║
# ║                                                            ║
# ╚════════════════════════════════════════════════════════════╝
#
# === ALPHA VERSION — P2P DISABLED BUT NOT REMOVED ===
# All P2P code is fully kept. Set BuildConfig.P2P_ENABLED = false
# (or equivalent runtime flag) to disable functionality.
# DO NOT REMOVE THESE RULES until P2P ships to production.
#
# Protection layers (5-layer defense):
#   1. -keep ................... prevent removal AND obfuscation
#   2. -keepclassmembers ...... reinforce member retention (whole-program analysis)
#   3. -keepnames ............. explicit class name preservation
#   4. -keepclassmembernames .. prevent method/field name obfuscation
#   5. includedescriptorclasses keep types in method signatures
#
# ⚠ REMOVED conflicting rule from previous version:
#   -keep,allowshrinking class com.aegisnav.app.p2p.** { *; }
#   ↑ allowshrinking PERMITTED R8 to remove "unused" P2P classes!

# P2P package: unconditional keep (no allow* modifiers)
-keep class com.aegisnav.app.p2p.** { *; }
-keepclassmembers class com.aegisnav.app.p2p.** { *; }
-keepnames class com.aegisnav.app.p2p.**
-keepclassmembernames class com.aegisnav.app.p2p.** { *; }
-keep,includedescriptorclasses class com.aegisnav.app.p2p.** { *; }

# P2PReportBundle (correlation package — outside p2p.** glob)
-keep class com.aegisnav.app.correlation.P2PReportBundle { *; }
-keepclassmembers class com.aegisnav.app.correlation.P2PReportBundle { *; }
-keepnames class com.aegisnav.app.correlation.P2PReportBundle
-keepclassmembernames class com.aegisnav.app.correlation.P2PReportBundle { *; }
-keep,includedescriptorclasses class com.aegisnav.app.correlation.P2PReportBundle { *; }


# ================================================
# TEST IN DEV BUILD:
# ================================================
# 1. ./gradlew assembleRelease              → zero errors
# 2. WorkManager scheduling                 → all 5 @HiltWorker classes
# 3. Room queries                           → all DAOs across 6 packages
# 4. GraphHopper offline routing            → A*/Dijkstra on GH 6.2
# 5. SQLCipher encrypted DB open/query      → net.sqlcipher 4.6.1
# 6. Gson deserialization                   → MapTileManifest @SerializedName
# 7. MapLibre map rendering                 → org.maplibre + com.mapbox
# 8. P2P: code present but disabled         → runtime flag OFF
# 9. Stack traces: readable                 → SourceFile + LineNumberTable
# 10. Verify P2P in release APK:
#     apkanalyzer dex packages --defined-only app-release.apk | grep p2p
#     → must show: P2PManager, IncomingReport, P2PSetupScreen, P2PConstants
# 11. Verify P2PReportBundle:
#     apkanalyzer dex packages --defined-only app-release.apk | grep P2PReportBundle
# ================================================


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 15: TINK (GOOGLE CRYPTO LIBRARY)                  ║
# ╚════════════════════════════════════════════════════════════╝

# Tink - Google's crypto library (used by SecureDataStore)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 16: SENTRY / GLITCHTIP CRASH REPORTING           ║
# ╚════════════════════════════════════════════════════════════╝

# SLF4J — transitive dependency from GraphHopper; no runtime impl needed
-dontwarn org.slf4j.**


# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 17: JAVAX.XML / JAXB (JACKSON XML TRANSITIVE)    ║
# ╚════════════════════════════════════════════════════════════╝

# Jackson XML (jackson-dataformat-xml) and JAXB module pull in javax.xml.stream
# and javax.xml.bind classes that don't exist on Android. These are never reached
# at runtime — GraphHopper uses Jackson for OSM/PBF parsing, not XML serialization.
-dontwarn javax.xml.stream.**
-dontwarn javax.xml.bind.**
-dontwarn javax.xml.bind.annotation.**
-dontwarn javax.xml.bind.annotation.adapters.**

# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 18: XMLGRAPHICS / JAVA.AWT (GRAPHHOPPER TRANSITIVE) ║
# ╚════════════════════════════════════════════════════════════╝

# xmlgraphics-commons is pulled in transitively by GraphHopper but is never
# reached at runtime on Android. It references java.awt.* desktop classes
# that don't exist on Android — suppress R8 missing-class errors.
-dontwarn org.apache.xmlgraphics.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.activation.**
-dontwarn com.fasterxml.jackson.module.jaxb.**

# ╔════════════════════════════════════════════════════════════╗
# ║  SECTION 19: PROTOBUF / OSMOSIS (GRAPHHOPPER TRANSITIVE)   ║
# ╚════════════════════════════════════════════════════════════╝

# GraphHopper pulls in osmosis-osm-binary which references protobuf classes
# not bundled on Android. Never reached at runtime — suppress R8 errors.
-dontwarn com.google.protobuf.**
-dontwarn org.openstreetmap.osmosis.**

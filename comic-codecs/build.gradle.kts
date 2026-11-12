plugins { alias(libs.plugins.android.library) }

// Compile upstream's Java/JNI bridge with our SDK. Its published AAR declares SDK 37.2,
// but these Java 11 sources use android.system APIs available since API 21. The native
// binaries also target API 21. This keeps the latest decoder without changing app SDKs.
val bindingSources by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val nativeBinaries by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val upstreamVersion = libs.versions.libarchive.get()
dependencies {
    bindingSources("me.zhanghai.android.libarchive:library:$upstreamVersion:sources@jar")
    nativeBinaries("me.zhanghai.android.libarchive:library:$upstreamVersion@aar")
    implementation("androidx.annotation:annotation:1.9.1")
}
val prepareBindings by tasks.registering(Sync::class) {
    from({ zipTree(bindingSources.singleFile) }) { include("**/*.java") }
    into(layout.buildDirectory.dir("generated/upstream-java"))
}
val prepareNative by tasks.registering(Sync::class) {
    from({ zipTree(nativeBinaries.singleFile) }) { include("jni/**", "proguard.txt") }
    into(layout.buildDirectory.dir("generated/upstream-native"))
}
android {
    namespace = "io.github.lonode.debridbooks.codecs"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles(layout.buildDirectory.file("generated/upstream-native/proguard.txt"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging { jniLibs.keepDebugSymbols += "**/libarchive-jni.so" }
    sourceSets.getByName("main") {
        java.srcDir(prepareBindings.map { it.destinationDir })
        jniLibs.srcDir(prepareNative.map { it.destinationDir.resolve("jni") })
    }
}
tasks.named("preBuild") { dependsOn(prepareBindings, prepareNative) }
dependencyLocking { lockAllConfigurations() }

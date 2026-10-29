plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionCode: Int by extra
val apkBuildTypes: List<String> by extra

android {
    namespace = "com.nexaai.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nexaai.assistant"
        minSdk = 28
        targetSdk = 35
        versionCode = appVersionCode
        versionName = project.version.toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        apkBuildTypes.forEach { maybeCreate(it) }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/nexaArtwork"))

    applicationVariants.all {
        val apkName = "${rootProject.name}.${buildType.name}.${versionName}.apk"
        outputs.all {
            (this as com.android.build.gradle.api.ApkVariantOutput).outputFileName = apkName
        }
    }
}

androidComponents {
    beforeVariants { variant ->
        variant.enable = variant.buildType in apkBuildTypes
    }
}

val prepareNexaArtwork by tasks.registering(Copy::class) {
    from(rootProject.file("images")) { include("*.png"); rename { it.replace('-', '_') } }
    into(layout.buildDirectory.dir("generated/nexaArtwork/drawable-nodpi"))
}
tasks.named("preBuild") { dependsOn(prepareNexaArtwork) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.k2fsa:sherpa-onnx:1.12.14@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
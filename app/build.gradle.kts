plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.carvoice"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.carvoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}

// Vosk's StorageService needs a uuid file next to the model in assets
val genUuid by tasks.registering {
    doLast {
        val dir = file("src/main/assets/model-ru")
        dir.mkdirs()
        File(dir, "uuid").writeText(java.util.UUID.randomUUID().toString())
    }
}
tasks.named("preBuild") { dependsOn(genUuid) }

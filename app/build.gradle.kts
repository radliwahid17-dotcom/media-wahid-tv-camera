plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.mediawahid.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.mediawahid.camera"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")

    val media3 = "1.10.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
}


val generateOriginalLogo by tasks.registering {
    doLast {
        val sourceDir = file("src/main/logo-source")
        val encoded = (1..5).joinToString("") { index ->
            file("$sourceDir/logo.part$index.b64").readText().trim()
        }
        val output = file("src/main/res/drawable-nodpi/media_wahid_logo_original.jpg")
        output.parentFile.mkdirs()
        output.writeBytes(java.util.Base64.getDecoder().decode(encoded))
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateOriginalLogo)
}

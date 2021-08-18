import java.io.File

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
}
val kotlinVersion by extra("1.5.21")
val ktorVersion by extra("1.6.2")

android {
    compileSdk = 30

    defaultConfig {
        applicationId = "se.svt.oss.videoplayer"
        minSdk = 21
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles.addAll(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), File("proguard-rules.pro")))

        }

    }
    buildFeatures {
        dataBinding = true
        viewBinding = true
    }
    compileSdkVersion(30)

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_1_8)
        targetCompatibility(JavaVersion.VERSION_1_8)
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.1.5")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("androidx.core:core-ktx:1.6.0")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("com.google.android.material:material:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}
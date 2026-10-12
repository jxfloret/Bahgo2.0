import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.bahgo20"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bahgo20"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OTP API Gateway configuration
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }
        
        // Priority: local.properties > gradle.properties
        val gatewayUsername = localProperties.getProperty("SMSGATEWAY_USERNAME") 
            ?: project.findProperty("SMSGATEWAY_USERNAME")?.toString() 
            ?: ""
        val gatewayPassword = localProperties.getProperty("SMSGATEWAY_PASSWORD") 
            ?: project.findProperty("SMSGATEWAY_PASSWORD")?.toString() 
            ?: ""

        buildConfigField("String", "SMSGATEWAY_USERNAME", "\"$gatewayUsername\"")
        buildConfigField("String", "SMSGATEWAY_PASSWORD", "\"$gatewayPassword\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.splashscreen)
    
    // OkHttp and Lifecycle for OTP implementation
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Import the BoM for the Firebase platform
    implementation(platform(libs.firebase.bom))

    // Firebase products
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Add Coroutines Play Services for .await()
    implementation(libs.kotlinx.coroutines.play.services)

    // Security
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

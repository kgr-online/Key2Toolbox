import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is configured from keystore.properties in the repo root
// (gitignored) when present; otherwise `assembleRelease` produces an unsigned
// APK. Keys: storeFile, storePassword, keyAlias, keyPassword
// storeFile is resolved relative to the app/ module directory.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.kgr.key2toolbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kgr.key2toolbox"
        minSdk = 28
        targetSdk = 34
        versionCode = 31
        versionName = "5.0.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
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
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "Key2Toolbox-${defaultConfig.versionName}-${name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Root shell access (https://github.com/topjohnwu/libsu)
    implementation("com.github.topjohnwu.libsu:core:5.2.2")

    // Xposed / LSPosed API stub - compile-only, provided by the framework at runtime.
    // Used by com.kgr.key2toolbox.xposed.RecentsHookInit to force the two-row Grid /
    // Masonry Overview layout inside Trebuchet (org.lineageos.trebuchet).
    compileOnly("de.robv.android.xposed:api:82")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

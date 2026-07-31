import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.example.hubengine"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps["STORE_FILE"] as String)
            storePassword = localProps["STORE_PASSWORD"] as String
            keyAlias = localProps["KEY_ALIAS"] as String
            keyPassword = localProps["KEY_PASSWORD"] as String
        }
    }

    defaultConfig {
        applicationId = "br.com.hubengine"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

val desktop = "${System.getProperty("user.home")}/Desktop"

afterEvaluate {
    tasks.named("assembleDebug") {
        doLast {
            copy {
                from("${layout.buildDirectory.get()}/outputs/apk/debug/app-debug.apk")
                into(desktop)
                rename { "HubEngine.apk" }
            }
        }
    }
    tasks.named("assembleRelease") {
        doLast {
            copy {
                from("${layout.buildDirectory.get()}/outputs/apk/release/app-release.apk")
                into(desktop)
                rename { "HubEngine-release.apk" }
            }
        }
    }
    tasks.named("bundleRelease") {
        doLast {
            copy {
                from("${layout.buildDirectory.get()}/outputs/bundle/release/app-release.aab")
                into(desktop)
                rename { "HubEngine-release.aab" }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

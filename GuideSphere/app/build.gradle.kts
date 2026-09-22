import java.util.Properties

plugins {
    // AGP 9 内置 Kotlin 支持（Kotlin 版本由 AGP 决定），无需 kotlin-android 插件
    id("com.android.application")
}

// ---------------------------------------------------------------------------
// 签名配置
//
// 仓库里**不放**签名私钥与口令。需要正式签名的 release 包时，在仓库根目录创建
// `keystore.properties`（已列入 .gitignore）：
//
//     storeFile=app/keystore/release.jks
//     storePassword=******
//     keyAlias=******
//     keyPassword=******
//
// 该文件不存在时，release 变体自动回退到 Android 的 debug 签名，
// 保证克隆下来就能直接 `assembleRelease` 出可安装的包。
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.remy.guidesphere"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.remy.guidesphere"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 纯 GL + Canvas 自绘，没有反射/序列化，混淆收益很低；
            // 关掉 shrinker 也便于对照 APK 体积与崩溃栈。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.11.0")

    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
}

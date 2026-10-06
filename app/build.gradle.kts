plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ir.hermes.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.hermes.mobile"
        minSdk = 26
        // targetSdk پایین نگه داشته میشود (مثل ترموکس): از Android 10 به بعد،
        // اپهایی که targetSdk ≥ 29 داشته باشند اجازه exec کردن باینری از پوشه
        // داده خودشان را ندارند (محدودیت SELinux). runtime تعبیهشدهٔ هرمس
        // (proot + rootfs) برای اجرا به این مجوز نیاز دارد.
        targetSdk = 28
        versionCode = 11
        versionName = "1.3.7"
        vectorDrawables { useSupportLibrary = true }
    }

    // کلید امضای ثابت پروژه. همهٔ بیلدها با همین کلید امضا می‌شوند تا نسخهٔ بعدی
    // بتواند روی نسخهٔ نصب‌شده آپدیت شود (وگرنه Android خطای «امضا مطابقت ندارد»
    // می‌دهد و باید اپ را حذف کرد). فایل خام در CI از keystore/hermes-release.p12.b64
    // ساخته می‌شود؛ اگر موجود نبود (بیلد محلی بدون کلید) به کلید دیباگ برمی‌گردیم.
    val stableKeystore = rootProject.file("keystore/hermes-release.p12")
    val hasStableKeystore = stableKeystore.exists()
    signingConfigs {
        if (hasStableKeystore) {
            create("stable") {
                storeFile = stableKeystore
                storeType = "PKCS12"
                storePassword = "hermesmobile"
                keyAlias = "hermes"
                keyPassword = "hermesmobile"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasStableKeystore) signingConfigs.getByName("stable")
                            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            // همان کلید ثابت برای دیباگ هم استفاده می‌شود تا آپدیت نسخهٔ دیباگ هم ممکن باشد.
            if (hasStableKeystore) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    // از Kotlin 2.0 به بعد نسخهٔ کامپایلر Compose از خود افزونهٔ
    // org.jetbrains.kotlin.plugin.compose می‌آید و composeOptions لازم نیست.
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // کتابخانههای bionic (proot و loader) باید به صورت فایل واقعی روی حافظه
        // استخراج شوند تا قابل اجرا باشند.
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        // app intentionally targets API 28 so the embedded proot runtime can exec
        // binaries from its data dir. ExpiredTargetSdkVersion only matters for
        // Google Play distribution; this app ships as a sideloaded APK, so we
        // disable that single check instead of changing targetSdk.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // 2024.12.01 → Compose 1.7.6 + Material3 1.3.1 (آخرین وصله‌های خط 1.7).
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

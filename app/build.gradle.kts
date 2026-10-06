plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // با Kotlin 2.x پلاگین کامپایلر Compose جداگانه اعمال می‌شود و
    // kotlinCompilerExtensionVersion دیگر لازم نیست.
    id("org.jetbrains.kotlin.plugin.compose")
}

// کلید امضای ثابت (از سکرت‌های CI یا متغیر محیطی).
// بدون آن، بیلد محلی با کلید دیباگ پیش‌فرض امضا می‌شود.
val keystoreFile = System.getenv("HERMES_KEYSTORE_FILE")?.let { File(it) }?.takeIf { it.exists() }
val keystorePassword = System.getenv("HERMES_KEYSTORE_PASSWORD") ?: ""
val keystoreAlias = System.getenv("HERMES_KEY_ALIAS") ?: "hermes"
val keystoreKeyPassword = System.getenv("HERMES_KEY_PASSWORD") ?: keystorePassword

android {
    namespace = "ir.hermes.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.hermes.mobile"
        minSdk = 26
        // targetSdk پایین نگه داشته می‌شود (مثل ترموکس): از Android 10 به بعد،
        // اپ‌هایی که targetSdk ≥ 29 داشته باشند اجازه exec کردن باینری از پوشه
        // داده خودشان را ندارند (محدودیت SELinux). runtime تعبیه‌شدهٔ هرمس
        // (proot + rootfs) برای اجرا به این مجوز نیاز دارد.
        targetSdk = 28
        versionCode = 3
        versionName = "2.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("hermes") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        // همین کلید برای هر دو نوع بیلد استفاده می‌شود تا نسخهٔ جدید بدون
        // حذف اپ قبلی روی آن نصب شود (امضای یکسان + همان applicationId).
        val signing = if (keystoreFile != null) signingConfigs.getByName("hermes") else signingConfigs.getByName("debug")
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
        debug {
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    // نسخهٔ کامپایلر از خودِ پلاگین Compose می‌آید و با runtime (BOM) هم‌خوان است.
    composeCompiler {}

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
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
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
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

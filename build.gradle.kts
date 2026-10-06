plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // پلاگین کامپایلر Compose هم‌نسخهٔ Kotlin است؛ استفاده از
    // kotlinCompilerExtensionVersion قدیمی همراه runtime جدید باعث
    // ناهمخوانی گروه‌های ترکیب‌بندی و کرش Composer می‌شد.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

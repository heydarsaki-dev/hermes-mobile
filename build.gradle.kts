plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // از Kotlin 2.0 به بعد کامپایلر Compose همراه خود Kotlin عرضه می‌شود.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rednoteseek.designsystem"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)
    // 公共组件（BsTopBar 的返回 / 关闭图标）只用 core 图标集
    implementation(libs.androidx.compose.material.icons.core)
    // 共享笔记卡片加载封面与头像
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Android 12 以下没有壁纸取色：从品牌红生成完整配色
    implementation(libs.material.kolor)

    testImplementation(libs.junit)
}

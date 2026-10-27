plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.josefelipe.trotinete"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.josefelipe.trotinete"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
    }

    // A chave de assinatura fica fora do repositório: caminho e senhas em ~/.gradle/gradle.properties.
    val chave = providers.gradleProperty("TROTINETE_KEYSTORE").orNull
    signingConfigs {
        if (chave != null) {
            create("release") {
                storeFile = file(chave)
                storePassword = providers.gradleProperty("TROTINETE_KEYSTORE_PASS").get()
                keyAlias = "trotinete"
                keyPassword = providers.gradleProperty("TROTINETE_KEYSTORE_PASS").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Igual à pública (R8 ligado) mas instalável ao lado da app normal, para testar sem perder a chave.
        create("ensaio") {
            initWith(getByName("release"))
            applicationIdSuffix = ".ensaio"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.code.scanner)
    // O code-scanner traz um fragment antigo, que o lint recusa na versão pública.
    implementation(libs.androidx.fragment)
    testImplementation(libs.junit)
}

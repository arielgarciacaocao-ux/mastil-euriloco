plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ar.mastil"
    compileSdk = 35

    defaultConfig {
        applicationId = "ar.mastil"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.4.2"
    }

    // Firma fija de la etapa de prueba: todas las versiones salen con la misma clave,
    // así Android deja actualizar encima sin desinstalar. Antes de vender se reemplaza
    // por una clave guardada como secreto, fuera del repositorio.
    signingConfigs {
        getByName("debug") {
            storeFile = file("firma-mastil.jks")
            storePassword = "mastil-etapa1"
            keyAlias = "mastil"
            keyPassword = "mastil-etapa1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Sin dependencias externas: solo Android y la biblioteca estándar de Kotlin.

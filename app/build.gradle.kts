plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nioro.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nioro.launcher"
        minSdk = 29
        targetSdk = 34
        // Cada compilación de GitHub Actions sube el número: así el actualizador sabe si hay versión nueva.
        // +43: el proyecto se mudó de cuenta y el contador de GitHub volvió a empezar; con la suma, las
        // versiones nuevas siguen por encima de la 1.43 que ya estaba instalada (si no, nunca se actualizaría
        // y Android rechazaría instalar un número menor). Debe coincidir con VERSION_OFFSET de build.yml.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt() + 43
        versionCode = run
        versionName = "1.$run"
    }

    signingConfigs {
        create("nioro") {
            // Clave fija incluida en el repo: así cada APK nuevo se instala ENCIMA del anterior.
            storeFile = file("nioro-launcher.keystore")
            storePassword = "nioro123"
            keyAlias = "nioro"
            keyPassword = "nioro123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nioro")
        }
        debug {
            signingConfig = signingConfigs.getByName("nioro")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Box2D (puerto Java puro) para la vista "Gravedad".
    implementation("org.jbox2d:jbox2d-library:2.2.1.1")
}

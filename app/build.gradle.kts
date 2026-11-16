plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.kasvan.ciceronfc"
    compileSdk = 35

    defaultConfig {
        applicationId = "dk.kasvan.ciceronfc"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-lokal"
    }

    // Fast nøgle, så nye versioner kan installeres oven på de gamle
    // uden at Cicero-indstillinger og login i app'en går tabt.
    signingConfigs {
        create("fast") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.keystore")
            // Adgangskoden ligger i GitHubs hemmelige boks (KEYSTORE_PASSWORD), ikke i koden
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "ciceronfc"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
    }

    // To udgaver af samme kode: den rigtige og en til Ciceros sandkasse (test). De har hver sit interne navn,
    // så begge kan være installeret på én gang, og hver sin port, så de ikke går i vejen for hinanden.
    buildFeatures {
        buildConfig = true
    }
    flavorDimensions += "miljoe"
    productFlavors {
        create("standard") {
            dimension = "miljoe"
            buildConfigField("boolean", "SANDBOX", "false")
            buildConfigField("String", "CICERO_HOST", "\"cicero.systematic.com\"")
            buildConfigField("String", "LOGIN_HOST", "\"auth.cicero.systematic.com\"")
            buildConfigField("String", "DOMAINS", "\"systematic.com\"")
            buildConfigField("int", "RFID_PORT", "1667")
            buildConfigField("String", "APK_NAME", "\"NFClibra.apk\"")
            manifestPlaceholders["appLabel"] = "NFClibra"
        }
        create("sandkasse") {
            dimension = "miljoe"
            applicationIdSuffix = ".sandkasse"
            buildConfigField("boolean", "SANDBOX", "true")
            buildConfigField("String", "CICERO_HOST", "\"cloud-education.nonprod.cicero-lms.com\"")
            buildConfigField("String", "LOGIN_HOST", "\"authentication-education.nonprod.cicero-lms.com\"")
            // Hjælp og andre links kan stadig pege på systematic.com
            buildConfigField("String", "DOMAINS", "\"cicero-lms.com,systematic.com\"")
            buildConfigField("int", "RFID_PORT", "1668")
            buildConfigField("String", "APK_NAME", "\"NFClibra-sandkasse.apk\"")
            manifestPlaceholders["appLabel"] = "NFClibra Sandkasse"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fast")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Enhedstests kører på byggemaskinen uden Android; Android-klasser svarer tomt i stedet for at fejle
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Værktøj til tags: kamera (CameraX) og genkendelse af tal og stregkoder (Googles ML Kit via Play-tjenester,
    // kører på telefonen uden net – modellerne hentes af Play-tjenester, så app'en ikke bliver stor)
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-mlkit-vision:1.4.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // Enhedstests (chip-format, billetnøgle, ændringsliste) – kører før hver udgivelse
    testImplementation("junit:junit:4.13.2")
}

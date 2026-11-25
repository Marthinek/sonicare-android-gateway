plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.marthas.sonicareble"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.marthas.sonicareble"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.1.35-queuefix5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.hivemq:hivemq-mqtt-client:1.3.10")

    testImplementation("junit:junit:4.13.2")
    // Real org.json: android.jar only has stubs in JVM unit tests.
    testImplementation("org.json:json:20250517")
}

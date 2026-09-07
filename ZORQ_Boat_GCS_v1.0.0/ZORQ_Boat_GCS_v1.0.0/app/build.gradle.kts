plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.pixhawkminigcs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.zorqboatgcs"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.0.0"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.dronefleet.mavlink:mavlink:1.1.11")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}

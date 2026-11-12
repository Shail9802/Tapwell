plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Samsung's proprietary SDK is supplied locally under its own license, never committed.
val samsungSdk = providers.gradleProperty("tapwellSamsungSdk").orNull?.let(::file)
require(samsungSdk == null || samsungSdk.isFile) { "tapwellSamsungSdk must point to the licensed Samsung AAR" }
val samsungApproved = providers.gradleProperty("tapwellSamsungApproved").orNull == "true"
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst { check(samsungSdk == null || samsungApproved) { "Samsung SDK release builds require partner approval; use debug for internal development" } }
}

android {
    namespace = "app.tapwell"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.tapwell"
        minSdk = 31
        targetSdk = 36
        versionCode = 24
        versionName = "2.4.0"
        buildConfigField("boolean", "SAMSUNG_HEALTH_APPROVED", (samsungSdk != null && samsungApproved).toString())
        resValue("string", "google_web_client_id", providers.gradleProperty("tapwellGoogleWebClientId")
            .getOrElse("722832906948-2226jgg6oogc8unq3608mihn0nl5tgff.apps.googleusercontent.com"))
        resValue("string", "google_cloud_project", providers.gradleProperty("tapwellGoogleProjectId")
            .getOrElse("tapwell-shail9802"))
    }

    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").java.srcDir(if (samsungSdk != null) "src/samsung/java" else "src/noSamsung/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    if (samsungSdk != null) {
        implementation(files(samsungSdk))
        implementation("com.google.code.gson:gson:2.11.0")
    }
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

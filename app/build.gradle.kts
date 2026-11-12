plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.sqldelight)
}

base {
    archivesName = "kinjar"
}

android {
    namespace = "app.kinjar"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kinjar"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "0.5.1"
        val syncEnabled = providers.gradleProperty("firebaseSyncEnabled").orElse("false").get().toBooleanStrict()
        buildConfigField("boolean", "FIREBASE_SYNC_ENABLED", syncEnabled.toString())
        listOf("PROJECT_ID", "APP_ID", "API_KEY", "WEB_CLIENT_ID").forEach { key ->
            val value = providers.environmentVariable("KINJAR_FIREBASE_$key").orElse("").get()
            require(value.none { it == '\n' || it == '\r' }) { "Invalid Firebase configuration" }
            require(!syncEnabled || value.isNotBlank()) { "KINJAR_FIREBASE_$key is required when Firebase sync is enabled" }
            buildConfigField("String", "FIREBASE_$key", "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }
    }

    signingConfigs {
        create("distribution") {
            storeFile = providers.environmentVariable("BUDGET_KEYSTORE_PATH").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("BUDGET_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("BUDGET_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("BUDGET_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("distribution")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

sqldelight {
    databases {
        create("KinjarDb") {
            packageName.set("app.kinjar.data.db")
            // Migrations live next to the .sq files; the version is derived from the highest N.sqm plus one.
            verifyMigrations.set(true)
        }
    }
}

dependencies {
    implementation(libs.work.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.appcheck)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.googleid)
    implementation(libs.coroutines.tasks)
    implementation(libs.appcompat)
    implementation(libs.biometric)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    testImplementation(libs.mockwebserver)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.sqldelight.android)
    implementation(libs.sqldelight.coroutines)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockito)
    testImplementation(libs.sqldelight.sqlite)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.coroutines.test)
}

tasks.register("checkDomainPurity") {
    description = "Fails if app.kinjar.domain imports Android classes"
    val domainDir = layout.projectDirectory.dir("src/main/kotlin/app/kinjar/domain")
    inputs.dir(domainDir)
    doLast {
        val offenders = domainDir.asFileTree.files.filter { file ->
            file.readLines().any { it.trim().startsWith("import android") || it.trim().startsWith("import androidx") }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Android imports in domain: ${offenders.joinToString { it.name }}")
        }
    }
}
tasks.named("check") { dependsOn("checkDomainPurity") }

val checkReleaseSigning by tasks.registering {
    val required = listOf("BUDGET_KEYSTORE_PATH", "BUDGET_KEYSTORE_PASSWORD", "BUDGET_KEY_ALIAS", "BUDGET_KEY_PASSWORD")
    val configured = required.all { !providers.environmentVariable(it).orNull.isNullOrBlank() }
    val keyFile = providers.environmentVariable("BUDGET_KEYSTORE_PATH").orNull?.let { file(it) }
    doLast {
        check(configured) {
            "Release signing is not configured. See docs/releasing.md."
        }
        check(keyFile?.isFile == true) {
            "Release keystore file is missing."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

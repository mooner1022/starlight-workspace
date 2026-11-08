import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.mooner1022.slplugin")
}

val workspaceVersion = "1.0.3"

android {
    namespace = "dev.mooner.starlightworkspace"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mooner.starlightworkspace"
        minSdk = 26
        targetSdk = 33
        versionCode = 4
        // The plugin version in the generated starlight.json.
        versionName = workspaceVersion
    }

    buildTypes {
        release {
            // Dependent plugins load these classes by name.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

starlight {
    sdk {
        // Build these from the StarLight repository: ./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease
        pluginCore = file("libs/PluginCore-release.aar")
        configDsl = file("libs/ConfigDSL-release.aar")
        excludeHostKotlinLibraries = true
    }
    // Generates assets/starlight.json; the version is versionName.
    manifest {
        id = "workspace"
        name = "Workspace"
        mainClass = "dev.mooner.starlightworkspace.StarlightWorkspacePlugin"
        // The PluginCore AAR here predates the API version table, so it can't be worked out from the code.
        apiVersion = "0.3.2"
        authors.add("mooner")
        description = "Shared access to StarLight projects (files, compiling, logs, debug room) for tool plugins such as MCP and IDE"
        category = "library"
    }
    deploy {
        // Pass -Pstarlight.device=<serial or ip:port> or set STARLIGHT_DEVICE.
        // Restarting force-stops StarLight on the device; opt in per run with -Pstarlight.restartApp=true.
    }
}

// The classes dependent plugins compile against: copy the jar to their app/libs and declare it compileOnly.
// It includes META-INF/*.kotlin_module, without which top-level functions don't resolve.
tasks.register<Jar>("apiJar") {
    val compileKotlin = tasks.named<KotlinCompile>("compileReleaseKotlin")
    dependsOn(compileKotlin)
    from(compileKotlin.flatMap { it.destinationDirectory })
    archiveFileName = "workspace-api-$workspaceVersion.jar"
    destinationDirectory = layout.buildDirectory.dir("outputs/api")
}

dependencies {
    // Provided by StarLight at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // Error locations of both script engines.
    testImplementation("dev.mooner.neonjs:neonjs-core:0.1.7")
    testImplementation("org.mozilla:rhino:1.7.15")
}

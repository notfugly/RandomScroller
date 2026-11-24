import java.io.DataOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    androidResources.noCompress += "words"
    namespace = "com.example.composejetpackjoyride"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.composejetpackjoyride"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("androidx.navigation:navigation-fragment-ktx:2.10.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.10.2")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    debugImplementation(libs.androidx.ui.tooling)
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
}

configurations.all {
    resolutionStrategy.eachDependency {
        if ((requested.group == "org.jetbrains.kotlin") && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("2.2.10")
        }
    }
}

@CacheableTask
abstract class IndexWords : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dictionary: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun index() {
        val words = dictionary.get().asFile.useLines { lines ->
            lines.map(String::trim)
                .filter(String::isNotEmpty)
                .map { it.toByteArray(Charsets.UTF_8) }
                .toList()
        }
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        DataOutputStream(directory.resolve("random.words").outputStream().buffered()).use { out ->
            out.writeInt(words.size)
            var offset = Int.SIZE_BYTES * (words.size + 2)
            for (word in words) {
                out.writeInt(offset)
                offset += word.size
            }
            out.writeInt(offset)
            for (word in words) out.write(word)
        }
    }
}

androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
    val indexWords = tasks.register<IndexWords>("index${variantName}Words") {
        dictionary.set(layout.projectDirectory.file("src/main/dictionary/random_words.txt"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(indexWords, IndexWords::outputDirectory)
}

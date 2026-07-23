import java.io.DataOutputStream
import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

abstract class GenerateRulexDbTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = sourceDir.get().asFile
        val output = outputDir.file("rulex/rulex.db").get().asFile
        output.parentFile.mkdirs()

        val sections = listOf(
            "explicit" to parseDictionary(File(source, "explicit.dict")),
            "implicit" to parseDictionary(File(source, "implicit.dict")),
            "lexicon" to parseRules(File(source, "lexicon.rules")),
            "general" to parseRules(File(source, "general.rules")),
            "prefix" to parseRules(File(source, "prefix.rules")),
            "correction" to parseRules(File(source, "correction.rules"))
        )

        DataOutputStream(output.outputStream().buffered()).use { out ->
            out.write(MAGIC)
            out.writeIntLe(sections.size)
            sections.forEach { (name, records) ->
                out.writeChunk(name.toByteArray(Charsets.US_ASCII))
                out.writeIntLe(records.size)
                records.forEach { record ->
                    out.writeChunk(record.key)
                    out.writeChunk(record.value)
                }
            }
        }
    }

    private data class Record(val key: ByteArray, val value: ByteArray)

    private fun parseDictionary(file: File): List<Record> {
        return parseLines(file).mapNotNull { line ->
            val split = line.indexOfFirst { it == SPACE || it == TAB }
            if (split <= 0 || split + 1 >= line.size) return@mapNotNull null
            val key = line.copyOfRange(0, split).trimAscii()
            val value = line.copyOfRange(split + 1, line.size).trimAscii()
            if (key.isEmpty() || value.isEmpty()) null else Record(key, value)
        }
    }

    private fun parseRules(file: File): List<Record> {
        return parseLines(file).mapNotNull { line ->
            val split = line.indexOfFirst { it == SPACE || it == TAB }
            if (split < 0) {
                if (line.isEmpty()) null else Record(line, ByteArray(0))
            } else {
                val pattern = line.copyOfRange(0, split).trimAscii()
                val replacement = line.copyOfRange(split + 1, line.size).trimAscii()
                if (pattern.isEmpty()) null else Record(pattern, replacement)
            }
        }
    }

    private fun parseLines(file: File): List<ByteArray> {
        val bytes = file.readBytes()
        val lines = ArrayList<ByteArray>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] == LF) {
                addLine(bytes, start, index, lines)
                start = index + 1
            }
        }
        addLine(bytes, start, bytes.size, lines)
        return lines
    }

    private fun addLine(bytes: ByteArray, start: Int, endExclusive: Int, out: MutableList<ByteArray>) {
        var end = endExclusive
        if (end > start && bytes[end - 1] == CR) end--
        val line = bytes.copyOfRange(start, end).trimAscii()
        if (line.isNotEmpty() && line[0] != HASH) {
            out += line
        }
    }

    private fun ByteArray.trimAscii(): ByteArray {
        var start = 0
        var end = size
        while (start < end && this[start].isAsciiWhitespace()) start++
        while (end > start && this[end - 1].isAsciiWhitespace()) end--
        return copyOfRange(start, end)
    }

    private fun Byte.isAsciiWhitespace(): Boolean {
        return this == SPACE || this == TAB || this == CR || this == LF
    }

    private fun DataOutputStream.writeChunk(bytes: ByteArray) {
        writeIntLe(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private companion object {
        val MAGIC: ByteArray = "RUTTS_RULEX_DB_V1".toByteArray(Charsets.US_ASCII)
        const val SPACE: Byte = 32
        const val TAB: Byte = 9
        const val CR: Byte = 13
        const val LF: Byte = 10
        const val HASH: Byte = 35
    }
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

val generatedRulexAssetsDir = layout.buildDirectory.dir("generated/rulexAssets")
val generateRulexDb by tasks.registering(GenerateRulexDbTask::class) {
    sourceDir.set(layout.projectDirectory.dir("src/main/cpp/third_party/rulex/data"))
    outputDir.set(generatedRulexAssetsDir)
}

android {
    namespace = "ru.hlebushek.RuTTS"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.hlebushek.RuTTS"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "0.6"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-std=c++20")
            }
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeType = keystoreProperties.getProperty("storeType", "JKS")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("universal") {
            dimension = "distribution"
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(generateRulexDb.flatMap { it.outputDir })
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.michaelrocks:libphonenumber-android:8.13.55")
    testImplementation("junit:junit:4.13.2")
}

tasks.configureEach {
    val taskName = name.lowercase()
    if (
        (taskName.startsWith("merge") && taskName.endsWith("assets")) ||
        taskName.contains("lint")
    ) {
        dependsOn(generateRulexDb)
    }
}

version = 43

dependencies {
    add("cloudstream", "com.lagradost.cloudstream3:cloudstream:pre-release")
}

cloudstream {
    authors     = listOf("Umut Can Yıldız")
    language    = "tr"
    description = "CanStreamHub — TMDB Keşif & Torrentio 4K + Yerli Kaynak Çözücü"
    status      = 1
    tvTypes     = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon", "Documentary")
    iconUrl     = "https://raw.githubusercontent.com/55umtyldz/canstream/main/assets/icon.png"
}

val bundledProviderModules = listOf(
    "FilmMakinesi",
    "HDFilmCehennemi",
    "SinemaCX",
    "SezonlukDizi",
    "StarTV",
    "DiziIzle",
    "KultFilmler",
    "Dizilla",
    "DiziYou",
    "HDFilmDelisi",
    "JetFilmIzle",
    "DiziKorea",
    "YesilCamTv",
    "FullHDFilmizlesene",
    "AnimeciX",
    "Animeler",
    "BelgeselX",
    "DramaDizilerim",
    "WebDramaTurkey",
    "SetFilmIzle",
    "Dizigecesi",
    "RareFilmm"
)

val copyBundledProviderSources = tasks.register<Copy>("copyBundledProviderSources") {
    bundledProviderModules.forEach { moduleName ->
        val providerDir = rootProject.file("$moduleName/src/main/kotlin")
        if (providerDir.exists()) {
            into(moduleName) {
                from(providerDir) {
                    exclude("**/*Plugin.kt")
                }
            }
        }
    }
    into(layout.buildDirectory.dir("generated/hubProviderSources"))
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(copyBundledProviderSources)
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            java.srcDir(copyBundledProviderSources.map { it.destinationDir })
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}

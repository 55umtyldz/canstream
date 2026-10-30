rootProject.name = "canstream"

val ignoredDirs = listOf(
    "tools",
    "docs",
    "config",
    "legacy",
    ".github",
    "gradle",
    "reports",
    "site",
    "core"
)

File(rootDir, ".").eachDir { dir ->
    if (!ignoredDirs.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

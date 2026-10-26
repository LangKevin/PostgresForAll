// Packaging for PostgresForAll: trimmed Java runtime, app image, and installers.
// Applied from app/build.gradle.kts; everything installer-related lives in installer/.
//
//   ./gradlew :app:jpackageImage       portable app image (no install)
//                                      Windows → app/build/jpackage/PostgresForAll/PostgresForAll.exe
//                                      macOS   → app/build/jpackage/PostgresForAll.app
//   ./gradlew :app:jpackageInstaller   installer for this OS → app/build/installer/
//                                      Windows → PostgresForAll-<version>-x64.msi   (per-user, no admin)
//                                      macOS   → PostgresForAll-<version>-arm64.dmg (or -x64)
//   ./gradlew :app:verifyRuntime       GUI E2E on the trimmed runtime (needs the E2E's database)
//
// Version: `version` in app/build.gradle.kts, or -PappVersion=1.2.3. MSI upgrades only
// replace an older version, so release versions must keep increasing.
// jpackage can't cross-build: make the MSI on Windows and the DMG on a Mac.

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.net.URI

val installerDir = rootProject.file("installer")
val appVersion = (findProperty("appVersion") as String?) ?: project.version.toString()

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.startsWith("windows")
val isMac = osName.startsWith("mac")
val arch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    else -> "x64"
}

/** Same UUID for every release: it's how Windows knows a new MSI replaces the old one. */
val windowsUpgradeUuid = "6b0f4c7e-3d1a-4f61-9a55-2c8e7f0d4b19"

/**
 * Java modules in the bundled runtime: jdeps' list (java.base … jdk.security.auth) plus
 * modules only reached at run time — TLS curves, JNDI, charsets, screen readers, Unsafe.
 */
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.management", "java.naming", "java.prefs", "java.security.jgss",
    "java.sql", "jdk.net", "jdk.security.auth", "jdk.crypto.ec", "jdk.charsets", "jdk.accessibility",
    "jdk.unsupported",
)

val toolchains = extensions.getByType(JavaToolchainService::class.java)
val jdkHome = toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.metadata.installationPath.asFile }
fun jdkTool(name: String) = jdkHome.map { it.resolve("bin/" + name + if (isWindows) ".exe" else "").absolutePath }

// ---- App icon in the window / taskbar ---------------------------------------

tasks.named<ProcessResources>("processResources") {
    from(File(installerDir, "icon/png")) { into("app-icon") }
}

// ---- Trimmed runtime ----------------------------------------------------------

val runtimeDir = layout.buildDirectory.dir("jpackage-runtime")

val jlinkRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the trimmed Java runtime bundled with the app."
    outputs.dir(runtimeDir)
    inputs.property("modules", runtimeModules)
    doFirst { delete(runtimeDir) }
    executable = jdkTool("jlink").get()
    args(
        "--add-modules", runtimeModules.joinToString(","),
        "--strip-debug", "--no-header-files", "--no-man-pages",
        "--compress", "zip-6",
        "--output", runtimeDir.get().asFile.absolutePath,
    )
}

// ---- Shared jpackage arguments --------------------------------------------------

val libDir = layout.buildDirectory.dir("install/PostgresForAll/lib")
val checkLauncherProps = layout.buildDirectory.file("jpackage-config/PostgresForAll-check.properties")

val writeCheckLauncherProps by tasks.registering {
    outputs.file(checkLauncherProps)
    doLast {
        val lines = mutableListOf("main-class=com.postgresforall.app.cli.CheckMain", "description=PostgresForAll health check")
        if (isWindows) lines += "win-console=true"
        checkLauncherProps.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n") + "\n") }
    }
}

fun commonJpackageArgs(): List<String> {
    val args = mutableListOf(
        "--name", "PostgresForAll",
        "--app-version", appVersion,
        "--vendor", "PostgresForAll",
        "--description", "PostgreSQL client: Object Explorer, query editor, table design and diagnostics",
        "--copyright", "PostgresForAll",
        "--input", libDir.get().asFile.absolutePath,
        "--main-jar", "postgresforall.jar",
        "--main-class", "com.postgresforall.app.Main",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--runtime-image", runtimeDir.get().asFile.absolutePath,
        "--add-launcher", "PostgresForAll-check=" + checkLauncherProps.get().asFile.absolutePath,
    )
    if (isWindows) args += listOf("--icon", File(installerDir, "windows/PostgresForAll.ico").absolutePath)
    if (isMac) args += listOf(
        "--icon", File(installerDir, "macos/PostgresForAll.icns").absolutePath,
        "--mac-package-identifier", "com.postgresforall",
        "--mac-package-name", "PostgresForAll",
        "--mac-app-category", "public.app-category.developer-tools",
    )
    return args
}

// ---- Portable app image -----------------------------------------------------------

tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Builds a self-contained PostgresForAll app image for this OS."
    dependsOn("installDist", writeCheckLauncherProps, jlinkRuntime)
    val dest = layout.buildDirectory.dir("jpackage")
    doFirst { delete(dest) }
    executable = jdkTool("jpackage").get()
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--type", "app-image") + commonJpackageArgs() + listOf("--dest", dest.get().asFile.absolutePath)
    })
}

// ---- WiX 3 (Windows) ---------------------------------------------------------------
// JDK 21's jpackage drives WiX 3 (candle / light). Use one on PATH or in %WIX%, else
// download the official 3.14.1 binaries once into the Gradle user home (no install, no admin).

val wixUrl = "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip"
val wixCache = File(gradle.gradleUserHomeDir, "pfa-tools/wix314")

fun wixOnPath(): File? {
    System.getenv("WIX")?.let { File(it, "bin").takeIf { d -> File(d, "candle.exe").isFile }?.let { d -> return d } }
    return System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map(::File).firstOrNull { File(it, "candle.exe").isFile && File(it, "light.exe").isFile }
}

val provisionWix by tasks.registering {
    description = "Finds or downloads WiX 3.14 for MSI builds."
    onlyIf { isWindows && wixOnPath() == null && !File(wixCache, "candle.exe").isFile }
    doLast {
        wixCache.mkdirs()
        val zip = File(wixCache.parentFile, "wix314-binaries.zip")
        logger.lifecycle("Downloading WiX 3.14 from $wixUrl")
        URI(wixUrl).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        project.copy { from(project.zipTree(zip)); into(wixCache) }
        zip.delete()
        check(File(wixCache, "candle.exe").isFile) { "WiX download didn't contain candle.exe" }
    }
}

// ---- Installer ------------------------------------------------------------------------

tasks.register<Exec>("jpackageInstaller") {
    group = "distribution"
    description = "Builds the installer for this OS: a per-user MSI on Windows, a DMG on macOS."
    dependsOn("installDist", writeCheckLauncherProps, jlinkRuntime, provisionWix)
    onlyIf { isWindows || isMac }
    val dest = layout.buildDirectory.dir("installer")
    doFirst {
        delete(dest)
        if (isWindows) {
            val wixBin = wixOnPath() ?: wixCache
            environment("PATH", wixBin.absolutePath + File.pathSeparator + System.getenv("PATH"))
        }
    }
    executable = jdkTool("jpackage").get()
    argumentProviders.add(CommandLineArgumentProvider {
        val type = if (isWindows) "msi" else "dmg"
        val extra = if (isWindows) listOf(
            "--win-per-user-install",
            "--win-menu", "--win-menu-group", "PostgresForAll",
            "--win-shortcut", "--win-shortcut-prompt",
            "--win-upgrade-uuid", windowsUpgradeUuid,
        ) else emptyList()
        listOf("--type", type) + commonJpackageArgs() + extra + listOf("--dest", dest.get().asFile.absolutePath)
    })
    doLast {
        // jpackage names it PostgresForAll-<version>.<ext>; add the architecture.
        val ext = if (isWindows) "msi" else "dmg"
        val made = dest.get().asFile.listFiles { f -> f.name.endsWith(".$ext") }?.singleOrNull()
            ?: throw GradleException("jpackage produced no .$ext in ${dest.get().asFile}")
        val target = File(made.parentFile, "PostgresForAll-$appVersion-$arch.$ext")
        if (made != target) check(made.renameTo(target)) { "Couldn't rename $made" }
        logger.lifecycle("Installer: ${target.absolutePath} (${target.length() / (1024 * 1024)} MB)")
    }
}

// ---- E2E on the trimmed runtime ------------------------------------------------------
// Proves the module list is complete: the GUI E2E (tools/e2e/E2E.java), compiled with the
// JDK, run by the bundled runtime's own java. Needs the same database / config as the E2E.

val e2eClasses = layout.buildDirectory.dir("e2e-classes")

val compileE2e by tasks.registering(Exec::class) {
    dependsOn("installDist")
    executable = jdkTool("javac").get()
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("-d", e2eClasses.get().asFile.absolutePath,
                "-cp", libDir.get().asFile.absolutePath + File.separator + "*",
                rootProject.file("tools/e2e/E2E.java").absolutePath)
    })
}

tasks.register<Exec>("verifyRuntime") {
    group = "verification"
    description = "Runs the GUI E2E on the trimmed runtime (shots in build/e2e-runtime-shots)."
    dependsOn(jlinkRuntime, compileE2e)
    executable = runtimeDir.get().asFile.resolve("bin/java" + if (isWindows) ".exe" else "").absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dpfa.theme=dark",
                "-cp", libDir.get().asFile.absolutePath + File.separator + "*" + File.pathSeparator + e2eClasses.get().asFile.absolutePath,
                "E2E", layout.buildDirectory.dir("e2e-runtime-shots").get().asFile.absolutePath)
    })
}

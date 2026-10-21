plugins { application }

version = "1.0.0"

dependencies {
    implementation(project(":core"))
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.formdev:flatlaf-extras:3.7.2")          // SVG icons, animated theme switch
    implementation("com.formdev:flatlaf-fonts-inter:4.1")       // UI font
    implementation("com.formdev:flatlaf-fonts-jetbrains-mono:2.304") // editor / grid font
    implementation("com.github.weisj:jsvg:2.2.0")               // SVG renderer used by FlatSVGIcon
    implementation("com.miglayout:miglayout-swing:11.5.2")      // dialog layouts
    implementation("net.java.dev.jna:jna-platform:5.19.1")      // Windows dark-mode registry read
    implementation("com.fifesoft:rsyntaxtextarea:4.0.1")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.6")
}

application {
    mainClass.set("com.postgresforall.app.Main")
    applicationName = "PostgresForAll"
}

tasks.jar {
    archiveFileName.set("postgresforall.jar")
    manifest { attributes("Main-Class" to "com.postgresforall.app.Main") }
}

// ---- Native app image ---------------------------------------------------
// `./gradlew :app:jpackageImage` builds a self-contained app (bundled Java
// runtime, no install needed) for the OS it runs on:
//   Windows → app/build/jpackage/PostgresForAll/PostgresForAll.exe
//             (+ PostgresForAll-check.exe, a console launcher for --check)
//   macOS   → app/build/jpackage/PostgresForAll.app
// jpackage can't cross-build: make the Mac app on a Mac, the .exe on Windows.

val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")
val isMac = System.getProperty("os.name").lowercase().startsWith("mac")

val jpackageTool = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}.map { it.metadata.installationPath.file(if (isWindows) "bin/jpackage.exe" else "bin/jpackage").asFile.absolutePath }

val writeCheckLauncherProps by tasks.registering {
    val props = layout.buildDirectory.file("jpackage-config/PostgresForAll-check.properties")
    outputs.file(props)
    doLast {
        val lines = mutableListOf("main-class=com.postgresforall.app.cli.CheckMain", "description=PostgresForAll health check")
        if (isWindows) lines += "win-console=true"
        props.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n") + "\n") }
    }
}

tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Builds a self-contained PostgresForAll app image for this OS."
    dependsOn(tasks.installDist, writeCheckLauncherProps)

    val libDir = layout.buildDirectory.dir("install/PostgresForAll/lib")
    val dest = layout.buildDirectory.dir("jpackage")
    val launcherProps = layout.buildDirectory.file("jpackage-config/PostgresForAll-check.properties")

    doFirst { delete(dest) }
    executable = jpackageTool.get()
    val argsList = mutableListOf(
        "--type", "app-image",
        "--name", "PostgresForAll",
        "--app-version", project.version.toString(),
        "--vendor", "PostgresForAll",
        "--input", libDir.get().asFile.absolutePath,
        "--main-jar", "postgresforall.jar",
        "--main-class", "com.postgresforall.app.Main",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--add-launcher", "PostgresForAll-check=" + launcherProps.get().asFile.absolutePath,
        "--dest", dest.get().asFile.absolutePath,
    )
    if (isMac) argsList += listOf("--mac-package-identifier", "com.postgresforall")
    args(argsList)
}

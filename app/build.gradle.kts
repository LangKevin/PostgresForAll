plugins { application }

version = "1.1.0"

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

// ---- Packaging ---------------------------------------------------------
// Trimmed runtime, app image (jpackageImage) and installers (jpackageInstaller):
// see installer/installer.gradle.kts and installer/README.md.
apply(from = rootProject.file("installer/installer.gradle.kts"))

// The zip/tar bundles of the plain application plugin aren't used (installers
// and app images come from installer/); skip the 54 MB they'd add to every build.
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }

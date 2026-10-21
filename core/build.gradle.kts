plugins { `java-library` }

dependencies {
    api("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    // DPAPI (Windows) and registry access for native-service discovery.
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    // macOS Keychain for remembered passwords.
    implementation("com.github.javakeyring:java-keyring:1.0.4")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.6")
}

tasks.test {
    // Live-DB tests read the same config the app does: an explicit
    // POSTGRESCONNECT_CONFIG (CI sets one), else your own copy in the data
    // folder (it holds the dev password), else the bundled default.
    val os = System.getProperty("os.name").lowercase()
    val dataDir = when {
        os.startsWith("windows") -> File(System.getenv("APPDATA") ?: "", "PostGresConnect")
        os.startsWith("mac") -> File(System.getProperty("user.home"), "Library/Application Support/PostGresConnect")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"), "PostGresConnect")
    }
    val userCopy = File(dataDir, "postgresconnect.config.json")
    val config = System.getenv("POSTGRESCONNECT_CONFIG")
        ?: (if (userCopy.isFile) userCopy else rootProject.file("app/src/main/resources/postgresconnect.config.json")).absolutePath
    environment("POSTGRESCONNECT_CONFIG", config)
    // The Mac CI job opts into tests that stop / start its Homebrew Postgres.
    System.getenv("PFA_MAC_CI")?.let { environment("PFA_MAC_CI", it) }
}

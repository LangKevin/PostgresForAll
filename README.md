# PostgresForAll

A PostgreSQL client in the style of SQL Server Management Studio, for Windows and macOS.
It's the Java port of PostGresConnect: Object Explorer, query tabs, backup and restore,
diagnostics, and a `--check` health command.

## Run it

You need JDK 21. The Gradle wrapper downloads Gradle itself.

```bash
./gradlew :app:run                     # GUI
./gradlew :app:run --args="--check"    # console health check
```

## Install

Download the installer for your OS from the repository's **Releases** page:
- **Windows:** `PostgresForAll-<version>-x64.msi` installs for your user, with no admin prompt.
- **macOS:** `PostgresForAll-<version>-arm64.dmg`. Drag the app onto Applications.

Neither installer is code-signed yet. [installer/RELEASE-NOTES.md](installer/RELEASE-NOTES.md)
explains the one-time SmartScreen / Gatekeeper step, upgrades and uninstalling.

## Build the installers or a portable app

```bash
./gradlew :app:jpackageInstaller   # MSI on Windows, DMG on macOS → app/build/installer/
./gradlew :app:jpackageImage       # portable app, no install → app/build/jpackage/
```

- **Self-contained:** both bundle a trimmed Java runtime, so users don't need Java.
- **Build per OS:** each OS must build its own installer.
- **Releasing:** pushing a `v*` tag builds, smoke-tests and publishes both installers.
- **Details:** packaging code and docs are in [installer/](installer/README.md).

## Testing

- `./gradlew build` runs the unit and live-database tests. Live tests skip when the
  configured server is down.
- `tools/e2e/E2E.java` drives the real GUI end to end; usage is in its header comment.
- CI (`.github/workflows/ci.yml`) runs everything on a GitHub macOS runner against a
  Homebrew PostgreSQL, and builds the Windows exe. Each run's Artifacts include the
  zipped `.app`, test reports and screenshots.
- `installer/windows/smoke-test.ps1` and `installer/macos/smoke-test.sh` install a built
  installer and check it. The release workflow runs both.
- [MAC-TESTING.md](MAC-TESTING.md) has the short hands-on checklist for what CI can't
  do: the admin password prompt, Finder, Docker Desktop.

## Settings

Connection defaults are in `postgresconnect.config.json`. To get an editable copy, use
**Help → Edit Settings File** and restart the app after changing it. Saved logins live in
`%APPDATA%\PostGresConnect` (Windows) or `~/Library/Application Support/PostGresConnect`
(macOS). On Windows, these are the same logins the original PostGresConnect app uses.

## `--check` exit codes

| Code | Meaning |
|---|---|
| 0 | OK |
| 2 | Nothing is listening on the port (TCP closed) |
| 3 | Login was refused |
| 4 | The test query failed |
| 5 | Docker is down |
| 6 | The container is missing |
| 7 | The server didn't come up after `--start` |

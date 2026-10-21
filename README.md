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

## Build an app you can double-click

```bash
./gradlew :app:jpackageImage
```

This builds a self-contained app with its own Java runtime, so users don't need Java
installed:

| Built on | Output |
|---|---|
| Windows | `app/build/jpackage/PostgresForAll/PostgresForAll.exe` (copy the whole folder), plus `PostgresForAll-check.exe` for the console check |
| macOS | `app/build/jpackage/PostgresForAll.app` |

Each OS must build its own app. The app isn't code-signed yet. On macOS, the first time
you open it, right-click the app → **Open**. On Windows, SmartScreen may show
"More info → Run anyway".

## Testing

- `./gradlew build` runs the unit and live-database tests. Live tests skip when the
  configured server is down.
- `tools/e2e/E2E.java` drives the real GUI end to end; usage is in its header comment.
- CI (`.github/workflows/ci.yml`) runs everything on a GitHub macOS runner against a
  Homebrew PostgreSQL, and builds the Windows exe. Each run's Artifacts include the
  zipped `.app`, test reports and screenshots.
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

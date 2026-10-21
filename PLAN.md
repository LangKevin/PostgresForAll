# PostgresForAll — Java port of PostGresConnect

Port of `C:\Users\langk\test\PostGresConnect` (C# / .NET 8 / WPF, Windows-only) to Java so one
codebase ships as a native app on **Windows (.exe / .msi)** and **macOS (.app / .dmg)**.

## Status (2026-10-21)

**Decisions:** Swing + FlatLaf (decision 1). Saved Windows logins are shared with the C# app
(decision 4). The C# app is left untouched. Installer vs. portable (decision 2) is deferred.
Signing (decision 3) isn't set up, since this is a dev tool. The product is named
PostgresForAll, but the data folder and config file keep the original names so saved
logins carry over.

**Done (phases 0–6, plus the build half of 7):**
- **Core and tests:** the core is fully ported and 227 JUnit tests pass, including the live
  ScheduleMe tests, a backup/restore round trip, and decrypting passwords the .NET app saved.
- **UI:** the whole UI is ported (login, Object Explorer, query tabs, DB operations,
  diagnostics, help, dialogs) along with the `--check` CLI.
- **Packaging:** `./gradlew :app:jpackageImage` builds a self-contained app image
  (Windows `.exe` verified; macOS `.app` not built yet).

**Deviations from §2–4:**
- **Docking:** split panes instead of ModernDocking. The original layout was fixed anyway.
  The output pane can be hidden from View.
- **Rebranding:** chrome and help text are platform-aware. The macOS menu bar,
  ⌘-shortcuts, ⌘Enter / ⌘. work, and Help → Edit Settings File creates a user copy of the
  config.
- **Small fixes found while porting:**
  - Tables with mixed-case names now script correctly.
  - The restore warning now ignores system tables when counting a database's tables.
  - The DB chip only turns red when the database itself is gone (SQLSTATE 3D000 / 55000),
    not for any "does not exist" error.
  - Unsaved tabs prompt before closing.

**Still to do:**
- **Verify on a Mac:** build the `.app` and test it end to end. That covers launchd/Homebrew
  discovery and start/stop, Keychain-saved logins, the admin prompt via osascript, `lsof`
  port owners, and Docker Desktop launch. None of this could run on this Windows machine;
  only the plist and `lsof` parsing is covered by tests.
- **Decide distribution format:** installer (WiX `.msi` / `.dmg`) or portable zip.
- **CI:** a Windows and macOS build matrix, plus a Postgres service container so the live
  tests run there.
- **Optional extras:** an app icon, plus code signing and notarisation if you share it
  outside the team.

## 1. What we're porting

SSMS-style PostgreSQL client, ~8–9k lines of hand-written C# (excluding generated `obj/`):

| Area | Source | Notes |
|---|---|---|
| Core (no UI) | `src/PostGresConnect.Core` — ~30 services/models | Npgsql, connection pools, query execution, schema inspection, script splitting, backup/restore via `pg_dump`/`pg_restore`, server discovery, Docker probing, log analysis, credential stores |
| UI | `src/PostGresConnect` — WPF + AvalonDock + AvalonEdit | Login window, docked Object Explorer tree, tabbed SQL editor + result grids + Messages, DB Operations output pane, diagnostics panel, ~9 dialogs. `MainWindow.xaml.cs` alone is 1,365 lines |
| CLI | `Cli/CheckCommand.cs` | `--check [--start]` health check with exit codes 0/2–7 |
| Tests | `tests/PostGresConnect.Tests` — xUnit, ~19 files | Live-DB tests auto-skip when the configured server is unreachable |

### Windows-only pieces that need a cross-platform replacement

| C# today | Why it's Windows-only | Java replacement |
|---|---|---|
| `PasswordStore`, `DatabaseCredentialStore` — DPAPI (`ProtectedData`) in `%APPDATA%\PostGresConnect\*.json` | DPAPI | `CredentialVault` interface: **Windows** → DPAPI via JNA `Crypt32Util` (same entropy `"PostGresConnect.v1"`, so existing saved passwords keep working); **macOS** → Keychain (`java-keyring` or `/usr/bin/security`). JSON files keep non-secret fields only on macOS |
| `%APPDATA%` paths | Windows folder | `AppPaths`: `%APPDATA%\PostGresConnect` / `~/Library/Application Support/PostGresConnect` |
| `WindowsPostgresServices` — registry + `sc query` | Registry / SCM | `LocalPgServiceSource` interface. Windows: JNA `Advapi32Util` registry + `sc query` (logic & regexes port 1:1). macOS: Homebrew (`brew services list`, `~/Library/LaunchAgents/homebrew.mxcl.postgresql*.plist`), Postgres.app (`~/Library/Application Support/Postgres/var-*`), EDB installer (`/Library/LaunchDaemons/postgresql-*.plist`); port from each data dir's `postgresql.conf` (reuse `ParsePort`) |
| `WindowsServiceHost` — `net start/stop` elevated via UAC | SCM + UAC | Keep for Windows (`ProcessBuilder` + `powershell Start-Process -Verb RunAs`). New `LaunchdHost` for macOS: `brew services start/stop` (no sudo) or `launchctl` via `osascript -e 'do shell script … with administrator privileges'` for system daemons |
| `PortOwnerDialog` — PowerShell `Get-NetTCPConnection` | PowerShell | Windows: same PowerShell call; macOS: `lsof -nP -iTCP:<port> -sTCP:LISTEN` |
| `PgToolSearch.InstallRoots` — `Program Files\PostgreSQL\*\bin` | Paths | Add macOS roots: `/opt/homebrew/opt/postgresql@*/bin`, `/usr/local/opt/postgresql@*/bin`, `/Applications/Postgres.app/Contents/Versions/*/bin`, `/Library/PostgreSQL/*/bin` |
| `App.xaml.cs` — `AttachConsole` for `--check` | kernel32 | Second jpackage launcher (`--add-launcher`) `postgresconnect-cli` with a console on Windows; on macOS the binary inside the `.app` runs fine from Terminal |
| `Process.Start(UseShellExecute)` to open files/URLs | Shell | `java.awt.Desktop.open/browse` |
| Window menu Win+Up/Down, custom min/max/close toolbar buttons | Windows chrome | Drop on macOS (native traffic lights); keep on Windows |

**macOS gotcha:** apps launched from Finder don't inherit the shell `PATH`, so `docker`, `pg_dump`, `brew` won't be found. `ProcessRunner` must prepend `/opt/homebrew/bin:/usr/local/bin:/Applications/Docker.app/Contents/Resources/bin` on macOS.

## 2. Target stack

| Concern | .NET today | Java choice |
|---|---|---|
| Language / runtime | .NET 8 | **Java 21 LTS** (Temurin 21 is already installed here) |
| Build | `dotnet` + `.slnx` | **Gradle** (Kotlin DSL) with wrapper — no global Gradle/Maven install needed |
| Postgres driver | Npgsql 10 + `NpgsqlDataSource` | **PgJDBC 42.7.x** + **HikariCP** (one pool per server+database, mirrors `ConnectionManagerFactory`) |
| UI toolkit | WPF | **Swing + FlatLaf** (recommended — see §6) |
| Docking | AvalonDock | **ModernDocking** (Swing) |
| SQL editor | AvalonEdit (TSQL highlighting) | **RSyntaxTextArea** — has a real SQL highlighter, line numbers, folding |
| Results grid | WPF `DataGrid` | `JTable` + custom `TableModel`, `TableRowSorter`; row cap banner as today |
| MVVM (`[ObservableProperty]`) | CommunityToolkit.Mvvm | Plain presenter classes + `PropertyChangeSupport`; background work on `ExecutorService`, UI updates via `SwingUtilities.invokeLater` |
| JSON config (with `//` comments) | System.Text.Json | **Jackson** with `ALLOW_COMMENTS` — same `postgresconnect.config.json` file format |
| Native access (DPAPI, registry) | P/Invoke | **JNA** + `jna-platform` |
| Tests | xUnit | **JUnit 5** + AssertJ; `@PostgresAvailable` → JUnit `ExecutionCondition` doing the same 1 s TCP probe |
| Packaging | `dotnet publish` | **jpackage** (bundles a trimmed JRE via jlink — users don't need Java installed) |

## 3. Project layout

```
PostgresForAll/
  settings.gradle.kts
  gradlew / gradlew.bat
  core/                      ← port of PostGresConnect.Core (no Swing imports)
    src/main/java/com/postgresforall/core/{config,model,service,platform}
    src/test/java/...        ← port of PostGresConnect.Tests
  app/                       ← Swing UI + CLI entry point
    src/main/java/com/postgresforall/app/{ui,presenter,dialogs,cli}
    src/main/resources/      ← icons, help text, default postgresconnect.config.json
  packaging/
    windows/  icon.ico, wix overrides
    macos/    icon.icns, Info.plist additions, entitlements.plist
  .github/workflows/release.yml
```

`core/.../platform/` holds every OS-specific interface (`CredentialVault`, `LocalPgServiceSource`,
`ServerHost`, `PortOwnerLookup`, `AppPaths`) with `windows/` and `macos/` implementations chosen once at
startup by `Platform.current()`. Nothing else in the codebase checks the OS.

## 4. Phases

### Phase 0 — Skeleton (½ day)
- Gradle multi-project, wrapper, Java 21 toolchain, Spotless/formatting, JUnit 5.
- `Platform` detection, `AppPaths`, `ProcessRunner` (port of `IProcessRunner`/`DefaultProcessRunner`/`IStreamingProcessRunner` with timeout + streaming + macOS PATH fix).
- CI: GitHub Actions matrix `windows-latest` + `macos-latest` running `./gradlew test`.

### Phase 1 — Core, pure logic first (2–3 days)
Port in this order; each comes with its ported tests, so behaviour is pinned before the UI exists:
1. `ScriptSplitter`, `DdlPredicates`, `GridSearch`, `LogAnalyzer`, `FileTextLoader` — pure functions, port tests verbatim.
2. Models: `ConnectionProfile` (+ SSL mode → PgJDBC `sslmode`), `QueryResult`/`ResultSet`, `SchemaNodes` (Java `record`s), `DiscoveredServer` + `ServerKey`, `OperationEvent`, `LogIssue`, `DiagnosticsResults`.
3. `AppConfig` + `ServerEntry` (Jackson, same JSON file and defaults: `localhost:5444 / scheduleme`).

### Phase 2 — Core, database layer (3–4 days)
- `ConnectionManager` (Hikari pool; `current_user` + `is_superuser` probe on connect) and `ConnectionManagerFactory` (per-database pools, leases).
- `QueryExecutor`: per-statement execution from `ScriptSplitter`, optional transaction wrap, `CREATE DATABASE` on a maintenance connection, row cap, **cancel via `Statement.cancel()`**, **NOTICE/WARNING via `Statement.getWarnings()` / `PSQLWarning`** (replaces Npgsql's `Notice` event — drain after each statement and between result sets).
- `SchemaInspector` — SQL is copied as-is; only the reader plumbing changes.
- `PostgresHandshake` (raw socket SSLRequest — direct port), `ServerDiscovery`, `ServerRegistry`, `ServerSession`.
- `DatabaseOperations`, `PgClientTools` (+ macOS roots), `PgToolRunner`, `ServerLogReader`; keep `IsBenignRestoreError` for `transaction_timeout`.
- Port the live-DB tests (`DbOwnerLoginQueryTests`, `SmartExecuteTests`, `BackupRestoreRoundTripTests`, …).

### Phase 3 — Platform layer (2–3 days)
- `CredentialVault`: Windows DPAPI (JNA) and macOS Keychain. **Migration test:** on Windows, the Java app must read a `profiles.json` written by the C# app.
- `LocalPgServiceSource`: Windows (registry + `sc`) and macOS (Homebrew / Postgres.app / EDB launchd).
- `ServerHost` implementations: `DockerHost` (docker CLI — same on both OSes), `WindowsServiceHost`, `LaunchdHost`, `UnmanagedHost`; `ServerHosts.forProfile` resolver.
- `ConnectionProbe`, `DockerProbe`, `DockerContainers`, `PortOwnerLookup`.
- Rule carried over: Docker is optional everywhere; a Docker-less machine must check green.

### Phase 4 — UI shell (4–6 days)
- `LoginWindow`: fields, "Connect as" presets (superuser host/port from config), Remember password, Discover servers.
- `MainWindow`: menu bar (native screen menu bar on macOS via `apple.laf.useScreenMenuBar`), toolbar with Server/Database combos, status bar with health dot, ModernDocking layout (Object Explorer left, document tabs right, DB Operations Output bottom).
- Object Explorer: `JTree` with lazy `TreeWillExpandListener` loaders (port of `TreeNodeViewModel.Loader` / `IsLoaded`), grey disconnected servers, tooltips, context menus, double-click to log in. Rebuild server node on `StateChanged`, retitle on `InfoChanged`.
- Query tab: RSyntaxTextArea (SQL), results `JTabbedPane` (Results / Messages), one `JTable` per result set, cap banner, copy / copy row / copy with headers, column sort & find-in-column, wrap-in-transaction toggle, DB chip.
- **Key bindings:** use `Toolkit.getMenuShortcutKeyMaskEx()` so Ctrl on Windows becomes ⌘ on macOS. F5 stays, plus **⌘/Ctrl+Enter** for Execute (F5 needs Fn on Mac laptops); Cancel = Esc / ⌘-. on macOS instead of Alt+Break.

### Phase 5 — Dialogs & diagnostics (3–4 days)
`DatabaseCredentialsDialog`, `TextInputDialog`, `ConfirmTypeDialog` (type-the-name to confirm), `ColumnFindDialog`, `HelpDialog`, `PortOwnerDialog`, `PostgresDiagnosticsDialog` + `DiagnosticsPanel`, `OperationOutputPanel`, `LogFixActions` (Docker-only fixes hidden for non-container servers; help text adjusted per OS). Backup / Restore / Restore-as-new with `JFileChooser` (or `FileDialog` on macOS for the native picker) and remembered folders (`AppSettings`).

### Phase 6 — CLI (½ day)
`postgresconnect --check [--start] [--host … --port …]` with identical output lines and exit codes (0, 2–7). Shared `main` dispatches CLI vs GUI on args; GUI path never touches the console.

### Phase 7 — Packaging (1–2 days + signing lead time)
`jpackage` **cannot cross-compile**: build the Windows package on Windows and the Mac package on a Mac (CI matrix does both).
- Use the `org.beryx.jlink` (badass-jlink) Gradle plugin to drive jlink + jpackage.
- **Windows:** `--type exe` or `msi` (needs WiX Toolset 3/5 on the build box) or `--type app-image` for a portable folder with `PostgresForAll.exe`; `--win-menu --win-shortcut --win-dir-chooser`; extra console launcher for the CLI. Optional Authenticode signing to avoid SmartScreen warnings.
- **macOS:** `--type dmg`, `--mac-package-identifier com.postgresforall`, universal story = build separately on Apple Silicon (`aarch64`) and Intel (`x64`) runners. Sign with a Developer ID cert and **notarize** (`xcrun notarytool`) or Gatekeeper will block it on other Macs; needs an Apple Developer account ($99/yr).
- Ship `postgresconnect.config.json` beside the app on Windows; on macOS copy the default into `~/Library/Application Support/PostGresConnect/` on first run (the `.app` bundle is read-only once signed).

### Phase 8 — Verification (1–2 days)
- Full test suite green on both CI runners (live-DB tests skip there unless a Postgres service container is added — add one, it's cheap).
- Manual checklist on both OSes against the ScheduleMe DB (`localhost:5444`): login, remember password, discovery, tree expansion, multi-statement execute with NOTICEs, cancel long query, transaction wrap, backup → restore round trip, restore-as-new, diagnostics, port owner, `--check` exit codes, HiDPI/Retina rendering, dark mode.

**Rough total: 3–4 weeks of focused work for one developer**, dominated by the UI (Phases 4–5).

## 5. Behaviour that must survive the port (from the original CLAUDE.md)
- Server identity = normalised `host:port`; one login per server; logins saved from Object Explorer are `IsServerLogin` and never pre-fill the login window.
- Superuser login triggers local discovery (config servers + local services + handshake sweep of `DiscoveryPorts`); servers with remembered logins connect silently, others show grey.
- New Query / scripting / DB operations target the selected tree node's server+database, else the active tab's.
- `pg_dump` 17+ `SET transaction_timeout` error forgiven on restore to older servers.
- Docker optional everywhere; host steps informational while the port answers.

## 6. Decisions to confirm

1. **Swing + FlatLaf vs JavaFX.** Recommending Swing: RSyntaxTextArea (SQL editor) and ModernDocking cover AvalonEdit/AvalonDock directly, `JTable` handles large result sets well, and FlatLaf looks modern on both OSes. JavaFX would need RichTextFX (no built-in SQL highlighter) and has no maintained docking library.
2. **Installer vs portable.** Installer (`.msi`/`.dmg`) or a zip of the app image?
3. **Code signing.** Do you have (or want) an Apple Developer ID and a Windows code-signing cert? Without them the app runs, but users see Gatekeeper/SmartScreen warnings.
4. **Credential compatibility.** Should the Java app on Windows read passwords saved by the existing C# app (plan assumes yes)?
5. **Name.** Keep "PostGresConnect" as the product name / config folder, or rename to "PostgresForAll"?

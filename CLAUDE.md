# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

PostgresForAll: a cross-platform (Windows + macOS) Java port of PostGresConnect
(a separate C# / WPF, Windows-only project kept outside this repository), an SSMS-style
PostgreSQL client: Object Explorer on the left, tabbed SQL editor with result grids on
the right, DB Operations Output at the bottom. Targets the ScheduleMe database by default.
Leave the original C# app untouched.

## Commands

```bash
./gradlew build                      # compile + all tests
./gradlew :core:test                 # tests (live-DB tests skip when the configured server is down)
./gradlew :core:test --tests '*QueryExecutorTest'
./gradlew :app:run                   # launch the GUI
./gradlew :app:run --args="--check"  # console health check (exit codes 0, 2-7)
./gradlew :app:jpackageImage         # portable self-contained app for this OS → app/build/jpackage/
./gradlew :app:jpackageInstaller     # MSI (Windows, per-user) / DMG (macOS) → app/build/installer/
./gradlew :app:verifyRuntime         # GUI E2E on the trimmed bundled runtime (needs the E2E env)
java -Dpfa.theme=dark -cp "app/build/install/PostgresForAll/lib/*" tools/e2e/E2E.java shots   # GUI E2E (after installDist)
```

Java 21 (Gradle toolchain); Gradle 8.14 via the wrapper — no global Gradle needed.
`jpackage` can't cross-build: build the `.app` on a Mac, the `.exe` on Windows.

**Packaging lives in `installer/`** (see its README):
- **What's there:** `installer.gradle.kts` is applied from `app/build.gradle.kts`. The folder also holds the icon master and generated icons, smoke tests and release notes.
- **Runtime:** the trimmed runtime's modules are `runtimeModules` there. Run `verifyRuntime` after adding a library.
- **Releases:** `.github/workflows/release.yml` runs on `v*` tags.
- **MSI rules:** the build needs WiX 3, and downloads it if missing. Versions must keep increasing. Never change `windowsUpgradeUuid`.

## Layout

- `core/` — no Swing. `config/` (AppConfig, same JSON as the original), `model/`,
  `service/` (everything that talks to Postgres, Docker or the OS tools),
  `platform/` (the only OS-specific code), `util/` (CancelToken, Json).
- `app/` — Swing + FlatLaf. `ui/MainFrame` (shell), `ui/LoginDialog`,
  `ui/explorer/ObjectExplorer` (lazy tree), `ui/query/QueryTab` (RSyntaxTextArea editor +
  `ResultGrid`s), `ui/diag/` (diagnostics, help, log fixes, port owner), `ui/dialogs/`,
  `cli/CheckCommand`. Default config: `app/src/main/resources/postgresconnect.config.json`.

## Conventions

- **Threading:** core APIs are blocking. UI handlers that do I/O wrap their body in
  `Ui.bg(owner, () -> ...)` (virtual thread) and touch Swing only through `Ui.edt` /
  `Ui.call` / `Ui.later`. `Ui.error/confirm/yesNo` and `FileDialogs` are safe from any
  thread. Never block the EDT on a query.
- **Cancellation:** `CancelToken` (register a callback that aborts the blocking call,
  e.g. `Statement.cancel()` or killing a process).
- **Processes:** arguments are always a `List<String>` (no quoting). `DefaultProcessRunner`
  resolves bare commands against PATH plus Homebrew/Docker/Postgres.app folders, because
  Finder-launched Mac apps don't get the shell PATH.
- **OS branching** happens once, in `platform/` (`Platform.current()`), or by choosing a
  `ServerHost` implementation: `DockerHost`, `WindowsServiceHost`, `LaunchdServiceHost`,
  `UnmanagedHost`.
- **Saved data compatibility:** `profiles.json`, `db-credentials.json` and `settings.json`
  live in the original app's folder (`%APPDATA%\PostGresConnect`,
  `~/Library/Application Support/PostGresConnect`) in the original JSON shape (PascalCase,
  SslMode as an int). On Windows passwords are DPAPI with the original entropy strings, so
  both apps read each other's saved logins; on macOS they go to the login Keychain
  (`KeyringSecretStore`) and the file holds `keychain:<id>`.
- **Theme / look:**
  - `ui/theme/Theme` handles light, dark (the default) and follow-system; switch with `Theme.setMode`.
  - Colours come only from `Palette.*`, whose PFA.* keys live in
    `app/src/main/resources/com/postgresforall/app/themes/*.properties` (shared look in
    `FlatLaf.properties`).
  - For static text colours, use FlatLaf style classes:
    `putClientProperty("FlatLaf.styleClass", "muted")`.
  - Icons are Lucide SVGs in `app/src/main/resources/icons`, loaded through
    `Icons.get` / `Icons.tinted`.
  - Fonts: `Fonts.mono` / `Fonts.ui` (Inter, JetBrains Mono).
  - `ThemeTest` fails on any hard-coded colour and checks WCAG AA contrast in all four themes.
- Docker is optional everywhere; host steps in `ConnectionProbe` are informational while
  the port answers.
- `pg_dump` 17+ archives set `transaction_timeout`; `DatabaseOperations.isBenignRestoreError`
  forgives exactly that on older servers.

## Who sees what

- **Superuser logins:** see every database on the server, plus the other servers
  (configured and discovered), silently connected with saved logins.
- **Any other login:** sees only the server it signed in to, and only the databases it
  has CONNECT on (`SchemaInspector.getDatabases`). File → Connect to Server still works
  with credentials.
- **Caveat:** PostgreSQL grants CONNECT to PUBLIC by default. To hide a database from
  ordinary roles, `REVOKE CONNECT ON DATABASE x FROM PUBLIC`.
- **Test:** `DatabaseVisibilityTest`.

## Object Explorer layout

With `CombineServerDatabases: true` (the default) the login server is the only root, and its
"Databases (all N servers)" folder holds one SERVER node per server. Each of those holds its
databases when connected, or is a grey "double-click to connect" entry. The login server's
group opens expanded. Group nodes are `Kind.SERVER`, so they get the server context menu.

## Adding a schema-tree node type

1. Add a record to `core/model/SchemaNodes`.
2. Add a query to `SchemaInspector`.
3. In `ObjectExplorer`, add a loader returning child `ExplorerNode`s and attach it with
   `withLoader(...)`; set `server` (and `databaseName` under a database) on every node.
4. Add an `ExplorerNode.Kind` and branch in `MainFrame.buildContextMenu` / `onTreeDoubleClick`.

## Table structure editing

- **Right-click menus:** every table and its structure folders (Columns, Keys, Constraints, Indexes, Triggers, Referenced by) have create / edit / rename / drop actions. They're wired in `ui/TableDesignActions`.
  - **Table menu:** a short top level (Design ▸, Script as SELECT/CREATE, Refresh, Properties). Every structure action, the diagram and dependencies sit under **Design ▸**.
  - **Properties:** opens `dialogs/TablePropertiesDialog`, an expandable `ui/PropertyTreeTable` (Property | Value) filled with a `PropertyNode` tree from `TablePropertiesInspector`. Rows with more detail carry children: columns, indexes, constraints, triggers, inbound FKs, the owner role, the size breakdown, policies and grants.
- **Dialogs:** they live in `ui/design`. Each extends `ChangeDialog`, which shows the SQL preview plus the lock each statement takes, and offers Apply / Open in query tab / Cancel. Dialogs only build statements; they never run them.
- **Core:**
  - `TableInspector` handles catalog reads and `scriptCreate`.
  - `DependencyInspector` reports what would break.
  - `DdlBuilder` produces SQL as `DdlStatement`, each carrying its lock level and whether it can run in a transaction.
  - `DdlApplier` runs one transaction with `SET LOCAL lock_timeout = '5s'`, then the CONCURRENTLY statements in autocommit.
- **After Apply:** the table node reloads through `ObjectExplorer.refreshTable(node, folderKind)`.
- **Large tables:** at or above `LargeTableRows` / `LargeTableBytes` in the config, the dialogs default to CONCURRENTLY or NOT VALID.
- **Tests:** `TableStructureLiveTest` creates and drops its own `pfa_tbl_*` databases. The E2E creates and drops `public.pfa_e2e_design`.
- **Relationship diagram:** "View relationship diagram" on a table opens a `ui/diagram/RelationshipDiagramTab` document tab.
  - **Data:** `RelationshipInspector.graph` walks foreign keys both ways for 1–3 hops, capped at 60 tables.
  - **Layout:** `DiagramLayout` puts referenced tables left and referencing tables right, wrapping a crowded hop past 6 tables per column. It is pure and unit-tested.
  - **Drawing:** `DiagramCanvas` paints crow's-foot lines and handles drag, pan, zoom and re-centring.
  - **Tests:** `RelationshipInspectorLiveTest` uses throwaway `pfa_rel_*` databases.

## Connection defaults

From `postgresconnect.config.json`: ScheduleMe at `localhost:5444` (Docker container
`scheduleme-postgres`); native PostgreSQL 18 superuser on 5432. Lookup order: env
`POSTGRESCONNECT_CONFIG`, beside the launcher, the data folder, the working directory,
then the bundled copy. Help → Edit Settings File creates a user copy in the data folder.

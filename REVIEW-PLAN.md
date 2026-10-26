# Review: performance, security, code quality and logging

**Date:** 2026-10-23. **Scope:** `core/` (7.5k lines), `app/` (12k lines), tests, `tools/e2e`,
CI and `installer/`. Four independent read-only reviews, one per area, with every finding
checked against the code. Nothing was changed.

**Overall:** the codebase is in good shape. The threading model is clean (no database
work on the UI thread anywhere), connections don't leak, cancellation is built in, and
SQL built from user input goes through proper quoting almost everywhere. The problems
below are specific sharp edges, and most of the high-impact ones are small fixes.

## 1. The ten things to fix first

| # | Area | Finding | Why it matters |
|---|---|---|---|
| 1 | Security | **"Script as SELECT / CREATE" doesn't escape quotes in names**, and double-clicking a table runs the result at once. `SchemaInspector.scriptTableSelect/Create` (`core/.../SchemaInspector.java:145-166`) | Anyone who can create a table can name it `x";DROP SCHEMA app CASCADE;--`. A superuser double-clicking it runs the DROP. |
| 2 | Security | **Backup / restore pass the database name as `--dbname=<name>`** (`core/.../PgToolRunner.java:55`). pg_dump treats a name containing `=` as a full connection string, including `host=` | A database named `dbname=x host=attacker` makes a backup send the admin's password elsewhere. |
| 3 | Security | **The bundled config describes the dev servers' superuser passwords** in a comment (`app/src/main/resources/postgresconnect.config.json:17-20`). It's in the repository and inside every jar, MSI and DMG | Anyone with the installer learns how to log in to those servers. |
| 4 | Performance | **Query results have no row cap** and are loaded fully into memory, twice (driver buffer plus the app's copy). `QueryExecutor.readTable` (`core/.../QueryExecutor.java:197-211`). An out-of-memory error isn't caught (`QueryTab.java:464-481`) | `SELECT *` on a large table takes the whole app down. |
| 5 | Correctness | **Backup, restore and the pre-restore check ignore per-database credentials** and use the server login (`DatabaseOperations.java:121-123, 257-261, 319-325, 397`). If the check fails it reports "0 tables", so a destructive restore gets the soft confirmation instead of the warning | A permission error makes a `pg_restore --clean` look harmless. |
| 6 | Logging | **There is no log of any kind.** No handler for crashes, `Ui.bgQuiet` drops every error silently (23 call sites), and the SLF4J "no-op" binding silences the connection pool and keyring libraries | When a user says "the app froze" or "the restore failed", there is nothing they can send. |
| 7 | Performance | **Opening a slow database blocks everything else on that server** for up to 15 s, because a global lock is held during the connect (`ConnectionManagerFactory.java:61-92`). The explorer holds its lock across the credentials prompt too (`ObjectExplorer.java:505-521`) | The UI appears hung while a dialog waits behind another window. |
| 8 | Security | **Saved superuser passwords are sent silently at startup** to every discovered server, with TLS set to "prefer" and no certificate checking (`MainFrame.java:302-321`, `ServerRegistry.connectSilently`) | A rogue listener on a stopped server's port receives the saved password. |
| 9 | Security | **Server text is rendered as HTML** in grid cells and tree labels. Nothing sets `html.disable` (`ResultGrid.java:183-189`, `ExplorerRenderer.java:59-78`) | A cell containing `<html><img src="file://...">` leaks the viewer's Windows credentials hash or sends a beacon. |
| 10 | Performance | **Sorting a large grid freezes the UI**: every numeric comparison creates two `BigDecimal`s, on the UI thread (`ResultGrid.java:106-108, 229-242`) | Clicking a column header on 200k rows freezes the app for seconds. |

## 2. Performance

### Findings
- **High**
  - **No row cap** (#4 above). Also, cancelling a query still has to drain the driver's buffer.
  - **Grid sort** (#10): `BigDecimal` per comparison, text fallback formats both cells every time.
- **Medium**
  - **Lock held across connect** (#7).
  - **Relationship diagram:** about 3 catalog round trips per table, each on a fresh pooled
    connection. 60 tables means roughly 180 round trips before it paints
    (`RelationshipInspector.java:64-137`).
  - **Table Properties:** about 11 sequential round trips, and the index list is fetched twice
    (`TablePropertiesInspector.java:213, 273`). `scriptCreate` runs 8 queries; `forColumn` 8.
  - **Server-wide queries wait the full 15 s pool timeout** before falling back to the
    maintenance database when the login database is offline (`SchemaInspector.java:179-188`).
    Every explorer rebuild and connection menu stalls 15 s after "Take database offline".
  - **Explorer filter** redraws every node on every keystroke with no debounce
    (`ObjectExplorer.java:186-223`).
  - **Grid cell renderer** allocates fonts and borders per cell, and hovering repaints the whole
    table (`ResultGrid.java:154-225`).
  - **Output panel** posts one UI task per log line; a verbose restore with thousands of objects
    makes the whole UI stutter (`OperationOutputPanel.java:221, 276-307`).
- **Low:** pools drop to zero idle connections after 2 min (a fresh handshake per query);
  the query text is split three times per run; icons are created inside cell renderers;
  the diagram recomputes edge geometry every paint; on macOS the theme watcher spawns a
  process every 5 s; the login window does keyring I/O on the UI thread before it appears;
  design actions re-query the table summary for every menu and dialog.

### Done well
Clean threading, no connection leaks (all 26 call sites use try-with-resources), sensible
caps elsewhere (25 result sets, 10k output lines, 1 MiB files, 60 diagram tables), parallel
discovery probes.

### Options
| Option | Work | What changes |
|---|---|---|
| **A. Quick wins** | 1 day | Row cap (default 10 000, in config) with a "truncated" banner; catch `OutOfMemoryError` in the query thread; stream results with `setFetchSize` instead of buffering; typed comparators for the grid sort; fetch the index list once in Properties. |
| **B. Responsiveness** | 2–3 days | A plus: per-database "opening" futures so one slow connect doesn't block others; release the explorer lock before the credentials prompt; route server-wide queries straight to the maintenance database; debounce the explorer filter (150 ms); batch output-panel appends on a 50 ms timer; cache renderer fonts and repaint only the hovered rows. |
| **C. Fewer round trips** | 2 days | B plus: one connection per inspector call; batch the diagram's queries per level (`ANY(?)` arrays); cache the table summary on the design action context; keep one idle connection per pool; a heap bound in the launcher (`-XX:MaxRAMPercentage=50`). |

## 3. Security

### Findings
- **High**
  - **Unescaped identifiers in Script as SELECT / CREATE** (#1). `DdlBuilder.ident` and every
    other builder are correct; this is the one place that quotes by hand. Smaller relatives: a
    table name with a newline breaks out of the SQL comment in the trigger template
    (`TableDesignActions.java:568-580`).
  - **`--dbname=` accepts a connection string** (#2).
- **Medium**
  - **Bundled config comment** (#3).
  - **HTML rendering of server text** (#9).
  - **Silent connects with saved passwords** (#8).
  - **TLS defaults to `prefer` with no certificate verification**; unknown SSL values in saved
    files also become `prefer` (`ConnectionProfile.java:21, 53`, `PgSslMode.fromJson`).
  - **Config and tool search can be hijacked**: a config file in the current working directory
    is honoured (`AppConfig.java:148`) and can set `PgBinPath`; `pg_dump` is searched on every
    PATH entry, relative ones included, and the highest version wins (`PgClientTools.locate`).
- **Low**
  - Saved passwords are pre-filled into fields with a reveal button.
  - Restoring an untrusted dump as superuser runs arbitrary SQL, and the confirmation doesn't
    say so.
  - Saved secrets aren't bound to their server (host/port).
  - Data files are written in place, not atomically; a truncated file reads as empty and is
    then overwritten, losing saved logins.
  - `WindowsServiceHost.java:68-78` builds an elevated `cmd.exe /c` string around the service
    name.
  - `DiscoveredServer` and `DockerContainers.PostgresEnv` would print a password through their
    generated `toString()`.
  - CSV export doesn't neutralise spreadsheet formulas (`=`, `+`, `-`, `@`).
  - The suggested backup file name comes from the database name unsanitised.
  - CI: `release.yml` interpolates the typed version into a shell script; `ci.yml` has no
    `permissions:` block; actions are pinned to tags, not commit SHAs; installers are unsigned;
    the E2E writes to the developer's real saved logins.
- **Informational:** PgJDBC 42.7.14 includes the fixes for CVE-2024-1597 and CVE-2025-49146.
  No known advisories for the other pinned versions. `java-keyring` 1.0.4 is old. Jackson is
  configured safely (no default typing).

### Done well
Bind parameters throughout the inspectors, `quote_ident(?)::regclass` for table lookups,
DDL previewed and confirmed, DROP DATABASE requires typing the name, passwords go to
pg_dump via `PGPASSWORD` never argv, DPAPI per user and Keychain on macOS with no plain-text
fallback, no credentials in JDBC URLs, `ConnectionProfile.toString` hides the password,
Docker credential reading off by default.

### Options
| Option | Work | What changes |
|---|---|---|
| **A. Close the holes** | ½ day | Use `DdlBuilder.ident` in SchemaInspector; reject database names containing `=` or whitespace in backup/restore (or pass `dbname='<escaped>'`); remove the password comment from the bundled config and change those dev passwords; set `html.disable` on grid and tree renderers; sanitise the suggested backup file name. |
| **B. Safer defaults** | 2 days | A plus: ask before the first silent connect to a discovered server (or leave it grey until clicked), and remember the choice per server; default new logins to `require`, with `verify-full` and a root-certificate field in the login dialog; warn once when a saved login still uses `prefer`; stop reading config from the working directory; only accept `pg_dump` from absolute, known install folders or an explicit `PgBinPath`; atomic file writes and `600` permissions on Linux; a `permissions:` block and SHA-pinned actions in CI; quote the version input in `release.yml`. |
| **C. Hardening** | 3 days | B plus: bind saved secrets to host/port; `channelBinding=require` where the server supports it; an "untrusted dump" warning on restore when logged in as superuser; CSV formula escaping; redacting `toString` on the two records; a Dependabot config; code signing for the installers (needs certificates, see INSTALLER-PLAN.md). |

## 4. Code quality

### Findings
- **High:** #4 (row cap / OOM) and #5 (credentials in DatabaseOperations, honest fingerprint).
- **Medium**
  - **Locks across dialogs and connects** (#7).
  - **The Output panel assumes one operation at a time**, but nothing enforces it
    (`OperationOutputPanel.java:202-242`). Start a restore then a backup: Cancel stops the
    newer one only, the progress bar mixes both, and the finishing toast names the wrong one.
  - **Switching a tab's connection while a query runs closes the pool under it**
    (`QueryTab.switchTo` :398-432), so the query dies with a pool-shutdown error.
  - **`ScriptSplitter` mis-parses `E'...\'...'` escape strings**, swallowing the next
    statement (`ScriptSplitter.java:52-69`). No tests for `E''`, `$1`, CRLF, comment-only
    statements.
  - **`DdlApplier` says "Nothing changed" after a cancelled CONCURRENTLY run** even though
    earlier statements committed (`DdlApplier.java:119-143`).
  - **Exceptions that hide real failures:** "Remember password" silently doesn't save when
    the file write fails (`PasswordStore.java:109-114`, `DatabaseCredentialStore.java:182-189`);
    a secret store returning null is stored as a null password; `newQueryOnSubmenu` stays at
    "Loading..." forever if the database list fails; `readRowsServerWide` drops the first error.
  - **`MainFrame` is 1692 lines** mixing shell, menus, backup/restore, credentials and tabs.
  - **The same JDBC `query(sql, mapper, params)` helper is copied into five classes.**
  - **No validation of config values:** an out-of-range `--port` makes the CLI exit with a
    stack trace instead of its documented codes (`CheckCommand.java:92-100`).
  - **The GUI E2E isn't part of `gradlew build`**, runs only on the macOS CI job, and writes to
    the developer's real saved logins (`E2E.java:86-94`). Live tests run `DROP DATABASE ... WITH
    (FORCE)` on whatever server the developer's config points at.
  - **WiX is downloaded without a checksum** (`installer.gradle.kts:130-150`).
- **Low:** `CancelToken.linked()` leaks registrations; explorer refresh races (an older load
  can win); dead code (`ObjectExplorer.model()`, `SchemaInspector.manager()`, a no-op tree
  listener); the login dialog hard-codes "v1.0" while the build says 1.1.0; duplicated
  dependency versions in two build files.

### Test coverage gaps
No tests for `ConnectionErrors` (which drives the credential-retry flow), `ServerSession`,
`ServerLogReader`, `DefaultProcessRunner.stream` (the cancel/kill path), the service-control
hosts, `CellText`, or the `PasswordStore` fallback when the secret store is unavailable.
`DdlApplier` cancellation and `DatabaseOperations` offline/online/drop are untested.

### Done well
Clean core/app split with real test seams, consistent threading discipline, cancellation
designed in, thorough resource handling, `ThemeTest` enforcing colour rules and contrast,
warning-free compile with all versions pinned.

### Options
| Option | Work | What changes |
|---|---|---|
| **A. Bugs** | 2 days | Credential-aware `DatabaseOperations` and a fingerprint that says "unknown" (always the warning prompt); one operation at a time in the Output panel (refuse with a toast); no connection switch while executing; `E''` strings in `ScriptSplitter` plus the missing tests; correct cancel message in `DdlApplier`; `PasswordStore.save` returns false and the UI says so; config and `--port` validation. |
| **B. Tests** | 2 days | A plus: `-Dpfa.dataDir` override so the E2E never touches real data; a Gradle `e2e` task so it's one command and runs in the Windows CI job too; replace the E2E's 14 fixed sleeps with condition waits; an opt-in gate (`PFA_LIVE_TESTS=1`) before live tests run against a non-CI server; tests for `ConnectionErrors`, `ScriptSplitter` edge cases and `DdlApplier` cancellation. |
| **C. Structure** | 3 days | B plus: a shared `CatalogQueries.query` helper replacing the five copies; split `MainFrame` into `DatabaseActions`, `ConnectionSwitcher` and `ConnectionPrompter`; a `gradle/libs.versions.toml`; WiX download checksum; read the version from the jar manifest; delete the dead code. |

## 5. Logging and diagnosability

### Findings
- **No application log.** No logging calls anywhere in `app/` or `core/`. `slf4j-nop` silences
  HikariCP and java-keyring. PgJDBC and FlatLaf log through `java.util.logging`, whose default
  goes to stderr, which a packaged GUI app doesn't have.
- **No crash handler.** `Main.java` sets none. UI-thread exceptions print to a stderr nobody
  sees; the window is left half-painted.
- **`Ui.bgQuiet` drops everything**, including `NullPointerException` and `OutOfMemoryError`,
  at 23 call sites. `Ui.bg` shows the message only, never the type or stack. The explorer's
  event executor captures exceptions in futures nobody reads (`ObjectExplorer.java:119-121`).
- **Silent failures that need a log line:** corrupt `profiles.json`, `db-credentials.json` or
  config (user edits are ignored with no signal); DPAPI or Keychain failures become a blank
  password and then "password authentication failed"; `pg_dump --version` failures are
  skipped, so "no client tools found" never says why; `pg_restore --list` errors are ignored.
- **No version shown anywhere**, no About dialog, and no Copy button on the Output panel or
  Diagnostics.
- **Support scenario "the app froze":** nothing to send. No thread dump path (the trimmed
  runtime has no `jstack`). "Login hangs on my Mac": can't tell whether it's the Keychain
  prompt, TLS fallback, DNS, or a slow `brew`/`docker` probe.

### Secrets to redact before any logging is added
`DiscoveredServer.suggestedPassword` and `DockerContainers.PostgresEnv` (record `toString`),
`PgToolRunner.Command.environment` (`PGPASSWORD`), `ConnectionProfile.jdbcProperties()`,
`AppConfig.password`, DPAPI blobs in `profiles.json` (decryptable by that Windows user), raw
`docker inspect` output, server log tails (`CREATE ROLE ... PASSWORD`), and executed SQL
(`ALTER USER ... PASSWORD`). Safe to log: `displayName()`, pool names, SQLSTATE.

### Library choice
**`java.util.logging`, configured in code, with `slf4j-jdk14` replacing `slf4j-nop`.** The
module is already in the trimmed runtime (pulled in by `java.sql`), so the installers don't
grow and the module list doesn't change. It's the only option that captures all the libraries
(PgJDBC, FlatLaf, HikariCP, keyring) without a bridge. Rotation is built in. Logback and
log4j2 need extra jars, a bridge, and bring their own advisory history for no benefit here.

### Options
| Option | Work | What the user sees |
|---|---|---|
| **A. Minimal** | 1 day | A rolling log in the data folder (`logs/postgresforall-N.log`, 2 MB × 5). Version, OS and Java logged at startup. A crash handler that logs the stack and shows one dialog pointing at the log. `Ui.bg` and `Ui.bgQuiet` log every failure. The ~15 "silent failure" sites log a warning. Help → **Open Log Folder**. |
| **B. Standard** | 3–5 days | A plus: a session id and operation ids (shown in the Output panel header) on every line; timing at the known stall points (connect, keyring, process launches, discovery); a redaction filter in the formatter with a unit test; Help → **Copy Diagnostics Bundle** (logs, probe results, redacted config and saved-login files, versions, a thread dump); Copy buttons on Output and Diagnostics; a log-level setting; `--check --verbose`. |
| **C. Advanced** | 1.5–2.5 weeks | B plus: an in-app log viewer tab; statement timing with a slow-query warning; pool metrics in the health tooltip; a crash dialog with "Copy details"; an opt-in audit log of every executed statement (user, database, duration, SQLSTATE; never result data). |

## 6. Recommended order

1. **Security A + the row cap** (1.5 days): the three security holes, HTML rendering, the row
   cap and OOM handling. Change the dev passwords named in the bundled config.
2. **Logging A** (1 day): without it, every later bug report is a guess.
3. **Code quality A** (2 days): credential-aware backup/restore, one operation at a time,
   splitter and applier fixes, validation.
4. **Performance B** (2–3 days): the lock scopes, filter debounce, output batching, grid sort.
5. **Tests B** (2 days): isolate the E2E from real data and run it on both CI jobs.
6. **Security B and Logging B** (4–7 days): safer connection defaults, the diagnostics bundle.
7. **Structure and the C tiers** as time allows.

Items 1–3 (about 4.5 days) remove every High finding. Items 1–5 (about 9 days) cover
everything rated Medium except the structural refactors.

## 7. Decisions for you

1. **Silent connects to discovered servers:** keep them (fastest start) or ask once per server
   (safer). Recommendation: ask once, remember the answer.
2. **TLS default for new logins:** keep `prefer` (works everywhere, no certificate checking)
   or `require` with a warning on downgrade. Recommendation: `require` for non-localhost.
3. **Row cap default:** 10 000 or 100 000 rows. Recommendation: 10 000, with the cap editable
   in the config and shown in the banner.
4. **Logging tier to start with:** A now and B later, or straight to B.
5. **Dev passwords:** the comment in the bundled config names the pattern for the test box's
   superusers. The fix is to remove the comment and change those passwords; say if you want
   me to do the first without the second.
6. **Live tests on `gradlew build`:** keep running them against your config by default, or
   require an opt-in flag so a plain build never drops databases on a real server.

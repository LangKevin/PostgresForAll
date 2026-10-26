# installer/

Everything that turns PostgresForAll into something people install: the icon, the bundled
Java runtime, the app image, the Windows MSI, the macOS DMG, smoke tests and release notes.
The design and decisions are in [../INSTALLER-PLAN.md](../INSTALLER-PLAN.md).

| Path | What it is |
|---|---|
| `installer.gradle.kts` | All packaging tasks. It's applied from `app/build.gradle.kts` |
| `icon/icon.svg` | The app icon master |
| `icon/MakeIcons.java` | Renders the SVG into the files below |
| `icon/png/icon-N.png` | Window and taskbar icons, copied into the app as `/app-icon/` |
| `windows/PostgresForAll.ico` | Icon for the launcher, Start menu and Add/Remove Programs |
| `windows/smoke-test.ps1` | Silently installs an MSI, checks it, and uninstalls it. `-UpgradeFrom` tests an upgrade |
| `macos/PostgresForAll.icns` | Icon for the `.app`, Dock and Finder |
| `macos/smoke-test.sh` | Mounts a DMG, copies the app out, and checks it |
| `RELEASE-NOTES.md` | Install instructions attached to every GitHub Release |
| `../.github/workflows/release.yml` | CI workflow that builds, smoke-tests and publishes the installers (GitHub needs it in `.github/`) |

## Build

```bash
./gradlew :app:jpackageInstaller                 # installer for this OS → app/build/installer/
./gradlew :app:jpackageInstaller -PappVersion=1.2.0
./gradlew :app:jpackageImage                     # portable app (no install) → app/build/jpackage/
./gradlew :app:jlinkRuntime                      # just the trimmed Java runtime → app/build/jpackage-runtime/
```

- **Windows output:** `PostgresForAll-<version>-x64.msi`, about 49 MB.
  - **Install:** per-user to `%LOCALAPPDATA%\PostgresForAll`, with no admin prompt. It adds a Start
    menu entry, an optional desktop shortcut, and an entry in Add/Remove Programs.
  - **Upgrades:** a newer version replaces the old one in place.
- **macOS output:** `PostgresForAll-<version>-arm64.dmg` (or `-x64` on an Intel Mac). It must be
  built on a Mac; jpackage can't cross-build.

### Building the MSI
- **WiX 3:** JDK 21's jpackage drives **WiX 3** (`candle.exe` / `light.exe`). The build uses
  WiX 3 from `PATH` or `%WIX%`. If it finds neither, it downloads the official WiX 3.14.1
  binaries once into `~/.gradle/pfa-tools/wix314`, with no install and no admin.
- **WiX 4–6:** don't work with JDK 21's jpackage.

### Versions
- **Where it's set:** `version` in `app/build.gradle.kts`, or override it with `-PappVersion`.
- **Format:** numbers only (`1.2.3`).
- **Increase every release:** Windows only upgrades over a lower version.
- **Upgrade UUID:** `installer.gradle.kts` has a fixed `windowsUpgradeUuid`. Never change it,
  because it's how a new MSI finds and replaces the old one.

## Runtime

- **What's bundled:** a trimmed Java 21 (about 51 MB instead of the full 166 MB JDK) built by
  `jlink` from `runtimeModules` in `installer.gradle.kts`. That's jdeps' list plus modules
  that are only reached at run time.
- **When adding a library:** check that nothing is missing with the GUI end-to-end test on
  the trimmed runtime. It needs the same database and config as the normal E2E:

```bash
POSTGRESCONNECT_CONFIG=... APPDATA=<scratch dir> ./gradlew :app:verifyRuntime --no-daemon
```

## Icon

Edit `icon/icon.svg`, then regenerate and commit the outputs:

```bash
./gradlew :app:installDist
java -cp "app/build/install/PostgresForAll/lib/*" installer/icon/MakeIcons.java
```

## Test an installer

```powershell
# Windows: install, check, uninstall. Backs up and restores a same-named desktop shortcut.
pwsh installer/windows/smoke-test.ps1 app/build/installer/PostgresForAll-1.2.0-x64.msi -ExpectedVersion 1.2.0
# Upgrade path: an older MSI first, then this one must replace it.
pwsh installer/windows/smoke-test.ps1 <new.msi> -ExpectedVersion 1.2.0 -UpgradeFrom <old.msi>
```
```bash
# macOS
bash installer/macos/smoke-test.sh app/build/installer/PostgresForAll-1.2.0-arm64.dmg 1.2.0
```

## Release

```bash
# 1. Bump `version` in app/build.gradle.kts and commit.
git tag v1.2.0
git push origin v1.2.0
```

- **What the push does:** `.github/workflows/release.yml` builds the MSI (Windows runner) and
  the DMG (Apple Silicon runner) and smoke-tests each by installing it. It then publishes a
  GitHub Release with both installers, `SHA256SUMS.txt` and `RELEASE-NOTES.md`.
- **Dry run:** Actions → Release → Run workflow builds and tests without publishing.

## Not done (see the plan)

- **Code signing:** none, so Windows SmartScreen and macOS Gatekeeper warn on first run.
  The release notes explain how to get past them.
- **Intel Mac DMG:** not built in CI.

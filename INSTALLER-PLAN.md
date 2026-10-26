# Plan: installers for Windows (.msi) and macOS (.dmg)

**Goal:** someone with no Java and no build tools installs PostgresForAll with a
double-click.
- **Install:** it gets a Start menu / Applications entry and an icon.
- **Upgrade:** a newer version replaces the old one in place, keeping saved logins and settings.
- **Uninstall:** it removes the app cleanly and leaves the user's data alone.

## Status (2026-10-22)

Built. The code is in `installer/` (see [installer/README.md](installer/README.md)).

**Decisions:** all recommendations were taken:
- **Windows:** per-user MSI.
- **macOS:** Apple Silicon DMG only.
- **Signing:** none for now.
- **Distribution:** GitHub Releases.
- **Runtime:** trimmed.
- **First version:** 1.1.0.

**Done:**
- **Phase 0, WiX:** the build finds WiX 3 or downloads 3.14.1 into the Gradle home, with no
  system install. The WiX 6 on this PC is left alone.
- **Phase 1, icon:** an SVG master, plus `MakeIcons.java` writing `.ico`, `.icns` and PNGs
  directly. The app sets the icon on its windows and, on macOS, the Dock.
- **Phase 2, Gradle tasks:** `jpackageInstaller`, a shared argument list, `-PappVersion`,
  and a fixed upgrade UUID. `jpackageImage` moved into the same script.
- **Phase 3, Windows:** a 49 MB MSI. On this PC it installed per-user with no admin
  prompt, with the Start menu entry, Add/Remove Programs entry, health check and GUI all
  working. It upgraded 1.0.9 → 1.1.0 with one entry left, and uninstalled cleanly
  (`installer/windows/smoke-test.ps1`).
- **Phase 4, macOS:** the DMG task and `installer/macos/smoke-test.sh`. It runs on the CI
  Mac runner through the release workflow and still needs its first CI run.
- **Phase 5, release:** `.github/workflows/release.yml` builds and smoke-tests both installers
  on `v*` tags, or on demand, then publishes a GitHub Release with `SHA256SUMS.txt` and
  `installer/RELEASE-NOTES.md`.
- **Phase 6, smaller runtime:** 166 MB → 51 MB. The full GUI E2E passes on the trimmed
  runtime (`:app:verifyRuntime`).
- **Phase 8, docs:** README, `CLAUDE.md`, `installer/README.md`.

**Not done:** phase 7 (signing) and an Intel Mac DMG.

**Changes from the plan below:**
- **WiX:** downloaded on demand rather than installed system-wide.
- **Version check:** there's no separate check that the version increased. Release versions
  come from the tag, so they increase with the tags.
- **Install folder:** per-user installs go to `%LOCALAPPDATA%\PostgresForAll`, jpackage's
  per-user default.

## 1. Where we are today

| Area | Now |
|---|---|
| Build output | `./gradlew :app:jpackageImage` makes a portable app image: a `PostgresForAll\` folder with `.exe` on Windows, `PostgresForAll.app` on macOS |
| CI | Uploads the Windows folder and a zipped `.app` (Apple Silicon) as build artifacts. No installers, no releases |
| Install / upgrade / uninstall | Manual: copy a folder. The desktop shortcut points into `app/build/jpackage/`, which every rebuild deletes and recreates |
| Icon | None. The window, taskbar, Dock and shortcut show the default Java icon |
| Bundled Java runtime | The whole JDK (166 MB on disk), including the compiler, javadoc and other tools the app never uses |
| Signing | None. Windows SmartScreen and macOS Gatekeeper both warn |
| Version | Fixed at `1.0.0` in `app/build.gradle.kts`, never bumped |
| Windows toolchain | jpackage (JDK 21) builds MSI/EXE installers only with **WiX 3.x**. This PC has **WiX 6** (a `dotnet` tool), which JDK 21's jpackage can't use |

## 2. What users get

### Windows: `PostgresForAll-<version>-x64.msi`
- **Install:** double-click to install for the current user, under
  `%LOCALAPPDATA%\PostgresForAll`. No administrator prompt.
- **Shortcuts:** a Start menu entry (**PostgresForAll**), plus a desktop shortcut offered on
  a checkbox during setup.
- **Add/Remove Programs:** an entry with the icon, version and an uninstall button.
- **Upgrades:** running a newer MSI replaces the old version in place. Saved logins
  (`%APPDATA%\PostGresConnect`, still shared with the C# app) and settings are untouched.
- **Health check:** `PostgresForAll-check.exe` (the console health check) is installed
  alongside the app.

### macOS: `PostgresForAll-<version>-arm64.dmg` (plus `-x64.dmg` if Intel is wanted)
- **Install:** open the disk image and drag **PostgresForAll** onto the Applications shortcut
  in the window.
- **Data:** logins stay in the Keychain and settings in
  `~/Library/Application Support/PostGresConnect`. Upgrading (drag the new version over)
  and removing (drag to Trash) both leave them alone.
- **First launch while unsigned:** macOS blocks the app once. On macOS 15+, go to System
  Settings → Privacy & Security → **Open Anyway**. Right-click → Open no longer bypasses
  this on macOS 15+. The README will say this, and the release notes will include the
  `xattr -dr com.apple.quarantine` alternative.

## 3. How it works

### 3.1 Gradle
- **Shared arguments:** move the current `jpackageImage` arguments (name, version, vendor,
  main jar/class, the `-check` launcher, icon) into one shared function.
- **New task** `./gradlew :app:jpackageInstaller`, which builds the installer for the
  current OS:
  - **Windows:** `--type msi`, `--win-per-user-install`, `--win-menu`,
    `--win-menu-group PostgresForAll`, `--win-shortcut`, `--win-shortcut-prompt`,
    `--win-upgrade-uuid <fixed UUID>`, `--icon packaging/windows/PostgresForAll.ico`.
  - **macOS:** `--type dmg`, `--mac-package-name PostgresForAll`,
    `--mac-package-identifier com.postgresforall`,
    `--mac-app-category public.app-category.developer-tools`,
    `--icon packaging/macos/PostgresForAll.icns`.
- **Output:** `app/build/installer/`, named `PostgresForAll-<version>-<arch>.<ext>`.
- **Version:** stays in `app/build.gradle.kts`, overridable with `-PappVersion=1.2.0`.
  - MSI only upgrades when the version increases, and it must be numeric `major.minor.patch`.
  - The upgrade UUID never changes: it is what makes a new MSI replace the old one rather
    than installing a second copy.
- **No cross-building:** jpackage can't cross-build, so the MSI is made on Windows and the
  DMG on a Mac (this Windows PC for the MSI, GitHub Actions for both).

### 3.2 Windows toolchain (WiX)
- **Recommended:** install **WiX Toolset 3.14** next to the existing WiX 6. They don't
  conflict, because WiX 3 is a separate install that jpackage finds through `PATH` /
  `WIX` (`candle.exe`, `light.exe`). This is jpackage 21's supported path.
- **Alternative:** run the newer jpackage from JDK 25 (which understands WiX 4+), while the
  app keeps compiling and running on Java 21. That's more moving parts, so only use it if
  WiX 3 becomes a problem.
- **CI:** install WiX 3.14 on the Windows runner (Chocolatey) rather than relying on the
  runner image to have it.

### 3.3 Icon
- **Design:** one SVG master in `packaging/icon.svg`: a database cylinder in Postgres blue
  with a "for all" mark, legible at 16 px.
- **Generating:** a small tool, `tools/icons/MakeIcons.java`, renders it with JSVG (already
  a dependency) to:
  - `PostgresForAll.ico` (16–256 px)
  - `PostgresForAll.icns` (16–1024 px; ICNS with PNG entries is simple to write directly,
    so no Mac is needed)
  - PNGs for the app window
- **Committed:** the generated files are committed, so builds don't depend on the tool.
- **Window icon:** the app also sets `setIconImages(...)` on its windows, so the title bar,
  taskbar and Alt-Tab show it too, not just the shortcut.

### 3.4 Smaller runtime (optional, after the installer works)
- **How:** `jdeps` on the app's jars gives the modules it actually needs. Expected:
  `java.base`, `java.desktop`, `java.sql`, `java.naming`, `java.management`,
  `java.logging`, `java.prefs`, `jdk.crypto.ec`, `jdk.unsupported`, `jdk.charsets`, plus
  `jdk.localedata` if locales matter. That list is passed via `--add-modules` with
  `--strip-debug --no-header-files --no-man-pages`.
- **Expected result:** roughly 166 MB → 60–80 MB installed, and a much smaller download.
- **Risk:** a missing module only fails at runtime (e.g. TLS or JNDI), so this is verified
  by running the GUI E2E against the packaged runtime, not just compiling.

### 3.5 Config and data
- **No config beside the launcher:** the installer does **not** put a
  `postgresconnect.config.json` next to the launcher. The install folder is replaced on every
  upgrade, so edits there would be lost.
- **Where config comes from:** the defaults come from the copy bundled in the jar. Help →
  Edit Settings File creates the user's own copy in the data folder, which upgrades never
  touch. The lookup order in `AppConfig` already supports this.
- **Uninstall:** leaves `%APPDATA%\PostGresConnect`, `~/Library/Application Support/PostGresConnect`
  and Keychain entries in place, like most apps. Removing them is a manual step, documented
  in the README.

### 3.6 Release pipeline (GitHub Actions)
New `release.yml`, triggered by pushing a tag `v*` (e.g. `v1.1.0`):

| Job | Builds | Smoke test before publishing |
|---|---|---|
| Windows | MSI | Silent per-user install (`msiexec /i … /qn`), run `PostgresForAll-check.exe` from the install folder, start the GUI and confirm it stays up, check the Start menu shortcut exists, then silent uninstall and confirm the folder is gone |
| macOS (Apple Silicon) | DMG | `hdiutil attach`, copy the app out, run `PostgresForAll-check` with the bare PATH a Finder-launched app gets, start the GUI, then detach |
| macOS (Intel, optional) | DMG | Same, on an Intel runner if GitHub still offers one |
| Publish | — | Creates a GitHub Release on the private repo with the installers and a `SHA256SUMS.txt` |

`ci.yml` stays as it is (tests, app image, E2E on every push), so installer builds don't
slow down normal pushes.

## 4. Phases

| # | Phase | Work | Size |
|---|---|---|---|
| 0 | Toolchain | Install WiX 3.14 here. Prove `jpackage --type msi` builds and installs a trivial MSI | ½ hour |
| 1 | Icon | SVG master, `MakeIcons` tool, `.ico` / `.icns` / PNGs committed, window icons set in the app | ½ day |
| 2 | Gradle installers | Shared jpackage args, `jpackageInstaller` task, version property, fixed upgrade UUID | ½ day |
| 3 | Windows MSI on this PC | Install, upgrade from an older build, uninstall. Then swap your current desktop shortcut for the installed one (asking you first) | ½ day |
| 4 | macOS DMG | Build on the CI Mac runner, automated smoke test, and update `MAC-TESTING.md` with the hands-on checklist (Gatekeeper, Keychain, launchd discovery from Finder) | ½ day |
| 5 | Release workflow | `release.yml`, smoke tests, GitHub Release with checksums. A first `v1.1.0` tag | ½ day |
| 6 | Optional: smaller runtime | `jdeps` module list, E2E against the packaged runtime, size comparison | ½ day |
| 7 | Optional: signing | Windows (Azure Trusted Signing or an OV certificate) and macOS (Developer ID + notarisation, JVM entitlements). Only if the app goes beyond your own machines | 1 day + account setup |
| 8 | Docs | README "Install" section; `CLAUDE.md` commands; status in `PLAN.md` | ½ hour |

Phases 0–5 and 8 give working installers for both OSes. Phases 6–7 can follow independently.

## 5. Testing

**Windows (this PC and CI):**
- **Fresh install:** works without admin; the Start menu entry and desktop shortcut launch
  the app; the Add/Remove Programs entry shows the right name, version and icon.
- **Upgrade:** installing 1.1.0 over 1.0.x leaves exactly one entry in Add/Remove Programs.
  Saved logins still decrypt, and settings and the theme are kept.
- **Running app:** installing while the app is open gets Windows' "files in use" prompt,
  with no broken install.
- **Uninstall:** removes the install folder and shortcuts; `%APPDATA%\PostGresConnect` remains.
- **Packaged jars:** the GUI E2E runs against the installed jars, not the build folder, so
  the packaged bits are what's tested.

**macOS (CI plus one hands-on pass on a real Mac):**
- **Install:** the DMG mounts, drag-install works, and the app launches after the
  Gatekeeper "Open Anyway" step.
- **Finder launch:** Homebrew/launchd discovery still works with the minimal PATH a Finder
  launch gets.
- **Upgrade:** dragging a new version over keeps Keychain logins and settings.
- **Uninstall:** Trash removes the app and leaves the data.

## 6. Risks

| Risk | Mitigation |
|---|---|
| WiX version mismatch (WiX 6 here, JDK 21 needs WiX 3) | Phase 0 checks this before anything else; JDK 25's jpackage is the fallback |
| Unsigned installers trigger SmartScreen / Gatekeeper warnings | Documented first-run steps; signing is phase 7 if it matters |
| MSI refuses to upgrade when the version didn't increase | `jpackageInstaller` fails the build if the version isn't higher than the last release tag |
| Intel macOS runners being retired by GitHub | Apple Silicon is the default; Intel is optional |
| A trimmed runtime misses a module that's only needed at runtime | E2E against the packaged runtime; trimming is optional and separate |
| Old desktop shortcut points into the build folder | Replace it with the installer's shortcut in phase 3, with your OK |

## 7. Decisions for you

1. **Windows format:** **MSI (recommended)**, an EXE setup wrapper (same WiX underneath,
   nothing gained), or keep a portable zip as well.
2. **Install scope:** **per-user, no admin (recommended)**, or per-machine under
   `Program Files`, which needs admin rights on every install and upgrade.
3. **Mac architectures:** **Apple Silicon only (recommended unless someone uses an Intel
   Mac)**, or both.
4. **Code signing:** **none for now (recommended for an internal dev tool)**, or set up
   Windows signing and an Apple Developer ID ($99/yr) now.
5. **Where installers live:** **GitHub Releases on the private repo (recommended)**, or a
   shared drive.
6. **Smaller runtime:** **yes, after the installers work (recommended)**, or keep the full
   JDK runtime.
7. **First installer version:** **1.1.0 (recommended)**, since the table design, diagram and
   properties work makes it more than 1.0.0.

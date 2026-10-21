# Testing PostgresForAll on a Mac

## What CI already covers

The `macOS` job in `.github/workflows/ci.yml` runs on every push. It uses an Apple
Silicon Mac with PostgreSQL installed through Homebrew and running as a launchd
job (`brew services`). It checks:

- **Build and tests:** the full test suite, plus Mac-only tests in `MacPlatformIT`:
  - finding the Homebrew launchd job and reading its port from the data folder
  - stopping and starting the server through `brew services`
  - saving and reading a password in the Keychain
  - finding which process owns a port with `lsof`
  - finding `pg_dump` in a Homebrew Postgres that isn't on PATH
- **The packaged app:**
  - `PostgresForAll.app` builds.
  - Its console check runs with the reduced PATH a Finder-launched app gets.
  - The GUI starts and stays up.
- **GUI end-to-end** (`tools/e2e/E2E.java`):
  - log in, with the remembered password stored in the Keychain
  - run queries: several result sets, a notice, a warning, an error
  - browse the Object Explorer and script a table
  - open Diagnostics and confirm the launchd service is detected

Results are in the run's **Artifacts**:
- `macos-results`: test report and screenshots
- `PostgresForAll-macos-arm64`: the zipped `.app`

## What needs a person (about 15 minutes)

CI can't type an admin password or click through Finder. On a Mac, use either the
`PostgresForAll-macos-arm64` artifact from CI, or your own build: JDK 21, then
`./gradlew :app:jpackageImage`, which puts the app at `app/build/jpackage/PostgresForAll.app`.

1. **Finder launch:** unzip the app, then right-click → **Open** (it isn't signed yet)
   and confirm the warning.
   - [ ] The login window appears and the menu bar shows PostgresForAll menus.
2. **Settings:** choose **Help → Edit Settings File** and set Host / Port / user for a server
   you have. Restart the app.
   - [ ] The values pre-fill the login window.
3. **Keychain:** tick **Remember password** and connect, then quit and reopen the app.
   - [ ] The password is filled in.
   - [ ] Keychain Access shows an item named "PostgresForAll".
4. **Keyboard:** in a query tab, try the Mac shortcuts.
   - [ ] **⌘Enter** runs the query.
   - [ ] **⌘.** cancels a running `SELECT pg_sleep(30);`.
   - [ ] **⌘N**, **⌘O** and **⌘S** work.
5. **Native file dialogs:** use **Open**, **Save** and **Backup database…**.
   - [ ] The Mac's own file dialogs appear.
   - [ ] A backup of a small database completes and the file is written.
   - [ ] **Restore as new database…** from that file works.
6. **Admin prompt** (only if you have the EDB installer's PostgreSQL in
   `/Library/PostgreSQL`): open Diagnostics for that server and click **Stop**, then **Start**.
   - [ ] macOS asks for an administrator password.
   - [ ] Cancelling the prompt shows "cancelled at the administrator password prompt".
7. **Docker Desktop** (only if installed): quit Docker Desktop, open Diagnostics for a
   container-based server, and click **Start Docker Desktop**.
   - [ ] Docker launches.
   - [ ] The checks turn green after it starts.
8. **Postgres.app** (only if installed): log in as a superuser with
   "Also list the other PostgreSQL servers on this machine" ticked.
   - [ ] The Postgres.app server is listed with a "Postgres.app" label.
9. **Appearance:**
   - [ ] Fonts are crisp on a Retina screen.
   - [ ] The window can be resized, full-screened and closed with ⌘W / ⌘Q.
   - [ ] Quitting with an unsaved tab asks first.

If something fails, note the step and send any error text. The console launcher,
`PostgresForAll.app/Contents/MacOS/PostgresForAll-check`, prints a diagnosis you can paste.

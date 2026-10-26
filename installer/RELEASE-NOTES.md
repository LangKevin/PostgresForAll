## Install

**Windows:** download `PostgresForAll-<version>-x64.msi` and double-click it.
- **Install:** it installs for your user only, with no administrator prompt, and adds a Start menu entry. A desktop shortcut is optional.
- **SmartScreen:** the installer isn't code-signed. If Windows shows "Windows protected your PC", choose **More info → Run anyway**.
- **Upgrade:** run a newer `.msi`. It replaces the installed version and keeps your saved logins and settings.
- **Uninstall:** Settings → Apps → Installed apps → PostgresForAll → Uninstall.

**macOS (Apple Silicon):** download `PostgresForAll-<version>-arm64.dmg`, open it, and drag **PostgresForAll** onto **Applications**.
- **First launch:** the app isn't signed by Apple, so macOS blocks it once. To allow it:
  1. Open the app and click **Done** on the warning.
  2. Go to System Settings → **Privacy & Security**.
  3. Click **Open Anyway** next to PostgresForAll.
  4. Open the app again.
- **Alternative in Terminal:** `xattr -dr com.apple.quarantine /Applications/PostgresForAll.app`
- **Upgrade:** drag the new version onto Applications and replace the old one. Keychain logins and settings are kept.
- **Uninstall:** drag PostgresForAll from Applications to the Trash.

**Your data:** saved logins and settings live outside the app, so upgrading or uninstalling never touches them:
- **Windows:** `%APPDATA%\PostGresConnect`
- **macOS:** `~/Library/Application Support/PostGresConnect` and the Keychain

To customise connection defaults, use **Help → Edit Settings File** in the app.

**Checksums:** `SHA256SUMS.txt` lists the SHA-256 of each installer.
- **Windows:** `Get-FileHash <file>`
- **macOS:** `shasum -a 256 <file>`

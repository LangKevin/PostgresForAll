# UI modernization plan

> **Status (2026-10-21): phases 1–5 done.** Decisions: follow the system's light/dark
> setting, Postgres-blue accent, outline icons; no phase 6 extras.
>
> **Verified by:**
> - `ThemeTest`: WCAG AA contrast in all 4 themes, and no hard-coded colours
> - the GUI E2E test in light, dark and dark at 150% scaling, locally and on the CI Mac
>
> **Deviations:**
> - **Explorer search** highlights and jumps between matches among loaded nodes (dimming
>   the rest) instead of hiding non-matches.
> - **Windows title bar:** FlatLaf already embeds the menu bar there.
> - **Toolbar:** the Run options sit in a separate ▾ button beside Run.
> - **Login brand pane:** uses a fixed Postgres blue (`PFA.brand`) in both themes.

The goal is for PostgresForAll to look like a current desktop developer tool (in the
style of DataGrip, TablePlus or VS Code) on both Windows and macOS. It should follow
the system's light or dark mode and keep every existing feature and shortcut.

We stay on **Swing + FlatLaf**. Everything below adds to what's already there, so
nothing gets rewritten. Moving to JavaFX (for example with AtlantaFX) would mean
rebuilding the whole UI for a similar visual result.

## 1. Where it looks dated today

| Area | Now | Problem |
|---|---|---|
| Theme | FlatLaf Light / Mac Light only | No dark mode, and the app ignores the OS appearance setting |
| Colours | 16 files use hard-coded colours (`Palette.*`, 23 `new Color(...)`) | These break in a dark theme and don't match the theme's accent colour |
| Icons | Almost none: the toolbar uses text and Unicode glyphs (▶ ■ ●) | Looks plain, and the tree is hard to scan |
| Toolbar | A row of text buttons plus two combo boxes | Heavy and crowded |
| Title bar | Native window frame plus a separate menu bar (Windows) | Wastes vertical space; current apps merge these |
| Object Explorer | Tree rows built from HTML, no icons | No visual difference between server, database, table and column |
| Editor | Default RSyntaxTextArea colours | Doesn't follow the theme |
| Result grid | Standard JTable | Tight rows, no hover effect, NULLs only in italic grey |
| Panels | Fixed split panes; bordered output pane | Feels like 2010; the output pane can't be collapsed neatly |
| Dialogs and banners | Hand-coloured panels | Inconsistent with FlatLaf's built-in styling |
| Typography | Default system fonts | Inconsistent between Windows and macOS |

## 2. Java libraries to add

All are on Maven Central, actively maintained, and work with Java 21.

| Library | Version | Used for |
|---|---|---|
| `com.formdev:flatlaf-extras` | 3.7.2 | `FlatSVGIcon` (crisp, theme-tinted icons), `FlatAnimatedLafChange` (animated light/dark switch), `FlatUIDefaultsInspector` (development aid) |
| `com.formdev:flatlaf-intellij-themes` | 3.7.2 | Optional extra themes (e.g. One Dark, Arc Dark, Nord) for a theme picker |
| `com.formdev:flatlaf-fonts-inter` | 4.1 | **Inter** as the UI font on both OSes |
| `com.formdev:flatlaf-fonts-jetbrains-mono` | 2.304 | **JetBrains Mono** for the editor, grid and logs |
| `com.github.weisj:jsvg` | 2.2.0 | The SVG renderer `FlatSVGIcon` uses (faster and more accurate than the old default) |
| `com.miglayout:miglayout-swing` | 11.5.2 | Cleaner, more consistent spacing in the login and credential dialogs (replaces GridBagLayout) |
| `io.github.andrewauclair:modern-docking-*` | 1.4.9 | **Optional, phase 6:** tool windows you can drag and rearrange, like an IDE |
| `com.fifesoft:autocomplete` | 4.0.0 | **Optional, phase 6:** SQL keyword and table-name completion in the editor. A feature rather than looks, but it's what makes an editor feel current |

**Icons:** a curated set of about 40 **Lucide** or **Tabler** SVG icons (MIT licence) stored
in `app/src/main/resources/icons/`, loaded through `FlatSVGIcon` so they recolour with
the theme. I prefer this to Ikonli icon fonts because it uses the same tooling as FlatLaf
and gives sharper output on Retina and high-DPI Windows screens.

**Already in use, just configured further:** FlatLaf 3.7.2 for the system title bar,
accent colour, rounded corners and tab styles, and RSyntaxTextArea 4.0.1, which ships
`dark.xml` and `idea.xml` themes.

## 3. Phases

Each phase can ship on its own, and CI with its screenshots verifies each one on
Windows and macOS.

### Phase 1: Theme foundation (about 1 day)
1. **`Theme` service:**
   - Light, Dark and "Follow system" modes. On macOS the app follows the system
     appearance automatically; on Windows it reads the registry key
     `AppsUseLightTheme`.
   - Uses FlatLaf's Mac themes on macOS, or plain FlatLaf Light/Dark on Windows.
   - A **View → Appearance** menu, with the switch animated by `FlatAnimatedLafChange`.
   - The choice is saved in `settings.json`.
2. **Replace hard-coded colours with theme keys:**
   - `Palette` becomes a list of semantic colours (`success`, `warning`, `danger`,
     `muted`, `bannerInfo`, …) defined in a `PostgresForAll.properties` theme file,
     with separate light and dark values.
   - All 16 files read colours through `UIManager.getColor(...)`.
   - This is the step that makes dark mode possible.
3. **Global look:**
   - one accent colour (Postgres blue, `#336791`)
   - `Component.arc` / `Button.arc` = 8 for rounded corners
   - larger hit areas
   - `ScrollBar.showButtons=false` and thin scroll bars
   - underlined selected tabs instead of card-style tabs
4. **Fonts:** Inter for the UI and JetBrains Mono for the editor, result grid, messages
   and output, through `FlatInterFont.install()` and `FlatJetBrainsMonoFont.install()`.

### Phase 2: Window chrome and toolbar (about 1 day)
1. **Unified title bar:**
   - Windows: `JRootPane.menuBarEmbedded` puts the menus in the title bar, saving
     one row.
   - macOS: `apple.awt.fullWindowContent` plus `apple.awt.transparentTitleBar` gives a
     single toolbar row next to the traffic lights, like Xcode or TablePlus.
2. **Toolbar redesign:**
   - icon buttons with tooltips listing the shortcut: New query, Open, Save, Run,
     Stop, Refresh
   - a split **Run ▾** button: Run all, Run selection, Run in transaction
   - the Server and Database pickers become one **connection pill**
     ("● localhost:5444 › scheduleme ▾") with a server → database menu
3. **Status bar:** a small coloured connection dot and server version on the left;
   row count, elapsed time and cursor line/column on the right, in muted text.

### Phase 3: Object Explorer (about 1 day)
1. **Icons:** per node kind (server, database, schema, table, view, function, column,
   key, role, folder), with an active/connected badge on the server icon.
2. **Renderer:** replace the HTML-based renderer with a real `JPanel` renderer (icon +
   name + muted detail). It's faster and has no HTML artefacts, it truncates long
   details with an ellipsis, and it shows a lock on offline databases.
3. **Search box** above the tree that filters loaded nodes as you type, like VS Code's
   explorer.
4. **Hover highlight**, and wider row heights.
5. **Header strip:** an "Object Explorer" title with small Refresh and Collapse-all
   icon buttons, replacing the plain bold label.

### Phase 4: Editor and results (about 1.5 days)
1. **Editor:**
   - RSyntaxTextArea `idea.xml` and `dark.xml` themes, tracking the app theme
   - JetBrains Mono 13 with a current-line highlight
   - a subtle gutter and bracket matching
2. **Query tab header:**
   - the green and red DB chip becomes a theme-aware **badge** with a status dot
   - "Wrap in transaction" becomes a compact toggle button with a tooltip
   - "Load file…" moves into the editor toolbar menu
3. **Result grid:**
   - roomier rows (24 px) and a hover row
   - zebra rows from the theme
   - right-aligned numbers in a monospaced font
   - `NULL` shown as a rounded muted label
   - sticky header with sort arrows, and column type shown in the header tooltip
   - a mini toolbar per result: copy as TSV/CSV/JSON, export to CSV, row count
4. **Messages:** a list of items, each with a severity icon and a timestamp,
   replacing the plain text pane.
5. **Empty states:** "Run a query (F5 / ⌘↵) to see results", centred with an icon,
   instead of an empty grey area.

### Phase 5: Dialogs, Diagnostics and output (about 1 day)
1. **Login window:**
   - a two-column card: app name and a short tagline on the left, the form on the right
   - MigLayout spacing
   - a segmented control for Application user / Superuser
   - inline validation, and a password field with a show/hide button
2. **Diagnostics:**
   - each check in a card with a status icon (✓ / ⚠ / ✕) and a coloured outline
   - banners use FlatLaf's built-in styles with info, warning and error icons
   - log issues shown as cards
3. **DB Operations output:**
   - a collapsible bottom panel with a header ("Output · 2 running"), a progress bar
     in the header, and severity icons per line
   - a small **toast notification** when a long operation (backup or restore)
     finishes while the panel is hidden
4. **Dialog buttons** in OS order: OK on the right on macOS. Destructive actions use
   FlatLaf's red `JButton.buttonType` styling.

### Phase 6: Optional extras
1. **ModernDocking:** Object Explorer, Output and future panels become dockable,
   auto-hide tool windows, and the layout is saved between sessions.
2. **SQL autocomplete** (`com.fifesoft:autocomplete`): keywords plus table and column
   names from the Object Explorer cache.
3. **Command palette (⌘K / Ctrl+K):** a search-everything popup for actions, tables
   and open tabs.
4. **Theme picker** from `flatlaf-intellij-themes`.
5. **App icon:** an `.icns` for macOS and an `.ico` for Windows, wired into `jpackage`.

## 4. How we verify it

- **The existing E2E test** (`tools/e2e/E2E.java`) keeps passing. It finds components
  by type and text, so layout changes don't break it, and only renamed buttons need
  updating.
- **Screenshot comparison:** extend E2E to capture each screen in **light and dark**
  on Windows and macOS; CI already saves the screenshots as artifacts. I review them
  after every phase.
- **Accessibility checks:**
  - contrast of text against background (WCAG AA) in both themes
  - all actions reachable by keyboard
  - tooltips on every icon-only button
- **High-DPI check:** Windows at 150% and 200% scaling, and macOS Retina. SVG icons
  and FlatLaf scale cleanly; any bitmap would show up here.

## 5. Risks

| Risk | Mitigation |
|---|---|
| Dark mode reveals more hard-coded colours | Phase 1 removes them all and adds a test that fails if `new Color(` appears outside the theme file |
| macOS full-window-content title bar overlaps content | Pad the toolbar on the left of the traffic lights with FlatLaf's `macOS.window.buttonsSpacing` |
| ModernDocking changes the window structure | Kept optional and last |
| Look differs between OSes | Accepted on purpose: Mac-style themes on Mac, standard FlatLaf elsewhere, identical layout |

## 6. Decisions for you

1. **Default appearance:** follow the system (recommended), or always light?
2. **Accent colour:** Postgres blue `#336791` (recommended), or keep FlatLaf's default
   blue?
3. **Icon style:** outline icons (Lucide/Tabler, recommended), or filled
   (Material Design)?
4. **Phase 6 extras:** which, if any? Docking, autocomplete, command palette, theme
   picker, app icon.

Phases 1–5 take about **5–6 days of work**. Phase 1 alone gives dark mode and a
noticeably cleaner look.

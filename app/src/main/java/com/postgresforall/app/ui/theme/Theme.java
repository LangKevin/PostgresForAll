package com.postgresforall.app.ui.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.postgresforall.core.platform.DefaultProcessRunner;
import com.postgresforall.core.platform.Platform;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Light / dark / follow-the-system appearance. Call {@link #init} once before
 * any Swing component exists; {@link #setMode} switches live (animated). In
 * SYSTEM mode the OS setting is re-checked every few seconds, so flipping
 * macOS / Windows dark mode carries over without a restart.
 *
 * <p>Colours come from FlatLaf theme files in {@code com/postgresforall/app/themes}
 * (shared look in FlatLaf.properties, semantic PFA.* colours per light / dark).
 */
public final class Theme {
    private Theme() { }

    public enum Mode {
        SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public static Mode parse(String s) {
            if (s == null) return SYSTEM;
            for (var m : values()) if (m.name().equalsIgnoreCase(s.trim())) return m;
            return SYSTEM;
        }
    }

    private static volatile Mode mode = Mode.SYSTEM;
    private static volatile boolean dark;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static Timer systemWatch;

    /**
     * Installs fonts and theme files and sets up the look and feel. The
     * {@code pfa.theme} system property (light / dark / system) overrides
     * {@code saved} — used by the end-to-end screenshots.
     */
    public static void init(Mode saved) {
        FlatLaf.registerCustomDefaultsSource("com.postgresforall.app.themes");
        FlatInterFont.installLazy();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        FlatLaf.setPreferredLightFontFamily(FlatInterFont.FAMILY_LIGHT);
        FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD);
        FlatJetBrainsMonoFont.installLazy();
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);

        var forced = System.getProperty("pfa.theme");
        mode = forced != null ? Mode.parse(forced) : saved;
        if (Platform.isMac()) {
            // Native title bar appearance: follow the system unless forced.
            System.setProperty("apple.awt.application.appearance", switch (mode) {
                case LIGHT -> "NSAppearanceNameAqua";
                case DARK -> "NSAppearanceNameDarkAqua";
                case SYSTEM -> "system";
            });
        }
        dark = resolve(mode);
        setupLaf(dark);
        startSystemWatch();
    }

    public static Mode mode() {
        return mode;
    }

    public static boolean isDark() {
        return dark;
    }

    /** Runs after every theme switch, on the EDT (editor themes, cached colours...). */
    public static void onChange(Runnable r) {
        listeners.add(r);
    }

    public static void removeOnChange(Runnable r) {
        listeners.remove(r);
    }

    /** Switches appearance now (animated). Call on the EDT. */
    public static void setMode(Mode m) {
        mode = m;
        apply(resolve(m));
    }

    private static void apply(boolean wantDark) {
        if (wantDark == dark) return;
        dark = wantDark;
        FlatAnimatedLafChange.showSnapshot();
        setupLaf(wantDark);
        FlatLaf.updateUI();
        FlatAnimatedLafChange.hideSnapshotWithAnimation();
        for (var l : listeners) l.run();
    }

    private static void setupLaf(boolean d) {
        if (Platform.isMac()) {
            if (d) FlatMacDarkLaf.setup();
            else FlatMacLightLaf.setup();
        } else {
            if (d) FlatDarkLaf.setup();
            else FlatLightLaf.setup();
        }
    }

    private static boolean resolve(Mode m) {
        return switch (m) {
            case LIGHT -> false;
            case DARK -> true;
            case SYSTEM -> systemIsDark();
        };
    }

    private static void startSystemWatch() {
        if (systemWatch != null) return;
        systemWatch = new Timer(5000, e -> {
            if (mode != Mode.SYSTEM) return;
            Thread.ofVirtual().start(() -> {
                var sys = systemIsDark();
                if (sys != dark) SwingUtilities.invokeLater(() -> {
                    if (mode == Mode.SYSTEM) apply(sys);
                });
            });
        });
        systemWatch.start();
    }

    /** True when the OS is set to dark mode. */
    public static boolean systemIsDark() {
        try {
            return switch (Platform.current()) {
                case MAC -> {
                    var r = new DefaultProcessRunner().run("defaults",
                            List.of("read", "-g", "AppleInterfaceStyle"), Duration.ofSeconds(3));
                    yield r.exitCode() == 0 && r.stdOut().trim().toLowerCase(Locale.ROOT).startsWith("dark");
                }
                case WINDOWS -> {
                    var key = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
                    var root = com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER;
                    yield com.sun.jna.platform.win32.Advapi32Util.registryValueExists(root, key, "AppsUseLightTheme")
                            && com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(root, key, "AppsUseLightTheme") == 0;
                }
                case LINUX -> false;
            };
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }
}

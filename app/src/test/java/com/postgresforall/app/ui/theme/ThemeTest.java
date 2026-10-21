package com.postgresforall.app.ui.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards for the theme: every colour comes from it, and text colours stay readable. */
class ThemeTest {

    @BeforeAll
    static void registerThemeFiles() {
        System.setProperty("java.awt.headless", "true");
        FlatLaf.registerCustomDefaultsSource("com.postgresforall.app.themes");
    }

    private static void setup(String laf) {
        switch (laf) {
            case "light" -> FlatLightLaf.setup();
            case "dark" -> FlatDarkLaf.setup();
            case "macLight" -> FlatMacLightLaf.setup();
            case "macDark" -> FlatMacDarkLaf.setup();
            default -> throw new IllegalArgumentException(laf);
        }
    }

    /** Text colours used on panel / table backgrounds meet WCAG AA (4.5:1). */
    @ParameterizedTest
    @ValueSource(strings = {"light", "dark", "macLight", "macDark"})
    void semanticTextColours_meetWcagAA(String laf) {
        setup(laf);
        var text = List.of("PFA.muted", "PFA.subtle", "PFA.success", "PFA.warning", "PFA.danger",
                "PFA.critical", "PFA.caution", "PFA.notice");
        for (var bg : List.of("Panel.background", "Table.background", "Tree.background")) {
            var b = UIManager.getColor(bg);
            assertThat(b).as(bg).isNotNull();
            for (var key : text) {
                var c = UIManager.getColor(key);
                assertThat(c).as(laf + " " + key + " defined").isNotNull();
                assertThat(contrast(c, b)).as(laf + ": " + key + " on " + bg).isGreaterThanOrEqualTo(4.5);
            }
        }
        // Banner text uses the normal label colour on the banner background.
        var fg = UIManager.getColor("Label.foreground");
        for (var banner : List.of("PFA.bannerInfo", "PFA.bannerWarning", "PFA.bannerError", "PFA.bannerSuccess")) {
            assertThat(contrast(fg, UIManager.getColor(banner))).as(laf + ": text on " + banner).isGreaterThanOrEqualTo(4.5);
        }
    }

    /** No hard-coded colours outside Palette — they'd break the dark theme. */
    @Test
    void noHardCodedColoursOutsidePalette() throws IOException {
        var src = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(src)) {
            var offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("Palette.java"))
                    .filter(p -> {
                        try {
                            var s = Files.readString(p);
                            return s.contains("new Color(") || s.contains("Color.WHITE") || s.contains("Color.BLACK")
                                    || s.contains("Color.GRAY") || s.contains("Color.RED");
                        } catch (IOException e) {
                            return true;
                        }
                    })
                    .map(src::relativize).toList();
            assertThat(offenders).as("files with hard-coded colours (use Palette / theme keys)").isEmpty();
        }
    }

    static double contrast(Color a, Color b) {
        var la = luminance(a);
        var lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int v) {
        var s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}

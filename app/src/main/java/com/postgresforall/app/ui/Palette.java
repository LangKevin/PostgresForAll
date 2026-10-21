package com.postgresforall.app.ui;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Semantic colours from the active theme (PFA.* keys in
 * {@code com/postgresforall/app/themes}). Always read through here — never
 * hard-code a colour — so light and dark themes both work. Values are looked up
 * on each call, so code that colours at paint / event time follows a theme switch.
 */
public final class Palette {
    private Palette() { }

    private static Color c(String key, Color fallback) {
        var v = UIManager.getColor(key);
        return v != null ? v : fallback;
    }

    public static Color accent() { return c("PFA.accent", new Color(0x336791)); }
    public static Color success() { return c("PFA.success", new Color(0x1E7B34)); }
    public static Color warning() { return c("PFA.warning", new Color(0xA35400)); }
    public static Color danger() { return c("PFA.danger", new Color(0xC62828)); }
    public static Color critical() { return c("PFA.critical", new Color(0x6F3AA8)); }
    public static Color caution() { return c("PFA.caution", new Color(0x7D6200)); }
    public static Color notice() { return c("PFA.notice", new Color(0x1F5FA8)); }
    /** Secondary text. */
    public static Color muted() { return c("PFA.muted", new Color(0x5F6368)); }
    /** Tertiary text (details, hints). */
    public static Color subtle() { return c("PFA.subtle", new Color(0x6B6F75)); }
    /** Unavailable items (offline databases, idle indicators). */
    public static Color disabled() { return c("PFA.disabled", new Color(0xA8ABB0)); }
    public static Color border() { return c("PFA.border", new Color(0xDCDDE0)); }
    public static Color headerStrip() { return c("PFA.headerStrip", new Color(0xF5F6F8)); }
    public static Color hoverRow() { return c("PFA.hoverRow", new Color(0xEAF1F9)); }
    public static Color nullBackground() { return c("PFA.nullBackground", new Color(0xEEEFF1)); }
    public static Color chipText() { return c("PFA.chipText", Color.WHITE); }

    public static Color bannerInfo() { return c("PFA.bannerInfo", new Color(0xEAF2FB)); }
    public static Color bannerInfoBorder() { return c("PFA.bannerInfoBorder", new Color(0xB9D3EE)); }
    public static Color bannerWarning() { return c("PFA.bannerWarning", new Color(0xFFF4E0)); }
    public static Color bannerWarningBorder() { return c("PFA.bannerWarningBorder", new Color(0xF0C77A)); }
    public static Color bannerError() { return c("PFA.bannerError", new Color(0xFDECEC)); }
    public static Color bannerErrorBorder() { return c("PFA.bannerErrorBorder", new Color(0xF2B8B8)); }
    public static Color bannerSuccess() { return c("PFA.bannerSuccess", new Color(0xE9F7EC)); }
    public static Color bannerSuccessBorder() { return c("PFA.bannerSuccessBorder", new Color(0x9AC9A8)); }

    public static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}

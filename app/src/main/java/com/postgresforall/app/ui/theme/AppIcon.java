package com.postgresforall.app.ui.theme;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * The app icon at every size the OS may ask for (title bar, taskbar, Alt-Tab, Dock).
 * Rendered from installer/icon/icon.svg; Gradle copies the PNGs into /app-icon.
 */
public final class AppIcon {
    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    private static List<Image> images;

    private AppIcon() { }

    public static synchronized List<Image> images() {
        if (images == null) {
            var list = new ArrayList<Image>();
            for (var s : SIZES) {
                try (var in = AppIcon.class.getResourceAsStream("/app-icon/icon-" + s + ".png")) {
                    if (in != null) list.add(ImageIO.read(in));
                } catch (Exception ignored) {
                    // a missing size just falls back to the others
                }
            }
            images = List.copyOf(list);
        }
        return images;
    }

    /** Sets the icon on a top-level window (owned dialogs inherit their owner's). */
    public static void apply(Window w) {
        var imgs = images();
        if (!imgs.isEmpty()) w.setIconImages(imgs);
    }

    /** Dock icon on macOS when not running from the packaged .app (which has its own .icns). */
    public static void applyToTaskbar() {
        var imgs = images();
        if (imgs.isEmpty() || !Taskbar.isTaskbarSupported()) return;
        var tb = Taskbar.getTaskbar();
        if (!tb.isSupported(Taskbar.Feature.ICON_IMAGE)) return;
        try {
            tb.setIconImage(imgs.get(imgs.size() - 1));
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // not available on this platform
        }
    }
}

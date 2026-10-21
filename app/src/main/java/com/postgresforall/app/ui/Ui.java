package com.postgresforall.app.ui;

import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.service.ConnectionErrors;
import com.postgresforall.core.util.CancelToken.CancelledException;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Threading and dialog helpers. UI event handlers that do I/O run their body
 * on a virtual thread via {@link #bg}; that body touches Swing only through
 * {@link #edt} / {@link #call} (or the dialog helpers here, which are safe from
 * any thread). This is the Swing equivalent of the original's async handlers.
 */
public final class Ui {
    private Ui() { }

    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    /** Runs {@code work} off the EDT; an exception is shown to the user. */
    public static void bg(Component owner, Work work) {
        Thread.ofVirtual().name("ui-bg").start(() -> {
            try {
                work.run();
            } catch (CancelledException ignored) {
                // user cancelled; nothing to report
            } catch (Throwable ex) {
                error(owner, "PostgresForAll", ConnectionErrors.message(ex));
            }
        });
    }

    /** Runs {@code work} off the EDT, ignoring failures (best-effort refreshes). */
    public static void bgQuiet(Work work) {
        Thread.ofVirtual().name("ui-bg").start(() -> {
            try {
                work.run();
            } catch (Throwable ignored) {
                // best-effort
            }
        });
    }

    /** Runs {@code r} on the EDT and waits for it (directly when already on the EDT). */
    public static void edt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ex) {
            var cause = ex.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error e) throw e;
            throw new RuntimeException(cause);
        }
    }

    /** Computes a value on the EDT and returns it to the calling thread. */
    public static <T> T call(Supplier<T> s) {
        var ref = new AtomicReference<T>();
        edt(() -> ref.set(s.get()));
        return ref.get();
    }

    public static void later(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    // ---- Keys -------------------------------------------------------------

    /** Ctrl on Windows / Linux, Cmd on macOS. */
    public static int menuMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    public static KeyStroke menuKey(int keyCode) {
        return KeyStroke.getKeyStroke(keyCode, menuMask());
    }

    public static KeyStroke menuKey(int keyCode, int extraModifiers) {
        return KeyStroke.getKeyStroke(keyCode, menuMask() | extraModifiers);
    }

    /** "Ctrl+" or "⌘" for hint text. */
    public static String menuKeyText() {
        return Platform.isMac() ? "⌘" : "Ctrl+";
    }

    // ---- Dialogs (safe from any thread) ----------------------------------

    public static void error(Component owner, String title, String message) {
        show(owner, title, message, JOptionPane.ERROR_MESSAGE);
    }

    public static void warn(Component owner, String title, String message) {
        show(owner, title, message, JOptionPane.WARNING_MESSAGE);
    }

    public static void info(Component owner, String title, String message) {
        show(owner, title, message, JOptionPane.INFORMATION_MESSAGE);
    }

    private static void show(Component owner, String title, String message, int type) {
        edt(() -> JOptionPane.showMessageDialog(owner, messageComponent(message), title, type));
    }

    /** OK / Cancel; true on OK. */
    public static boolean confirm(Component owner, String title, String message, boolean warning) {
        return call(() -> JOptionPane.showConfirmDialog(owner, messageComponent(message), title,
                JOptionPane.OK_CANCEL_OPTION,
                warning ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION);
    }

    /** Yes / No; true on Yes. */
    public static boolean yesNo(Component owner, String title, String message, boolean warning) {
        return call(() -> JOptionPane.showConfirmDialog(owner, messageComponent(message), title,
                JOptionPane.YES_NO_OPTION,
                warning ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION);
    }

    /** Long messages wrap instead of producing a screen-wide dialog. */
    public static JComponent messageComponent(String message) {
        var lines = message.split("\n", -1);
        var longest = 0;
        for (var l : lines) longest = Math.max(longest, l.length());
        var area = new JTextArea(message);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setBorder(null);
        area.setFont(UIManager.getFont("Label.font"));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(Math.min(64, Math.max(24, longest)));
        area.setRows(0);
        area.setSize(area.getPreferredSize());
        if (lines.length > 30) {
            area.setRows(30);
            var sp = new JScrollPane(area);
            sp.setBorder(BorderFactory.createEmptyBorder());
            return sp;
        }
        return area;
    }

    public static void busy(Component c, boolean busy) {
        edt(() -> {
            var w = c instanceof Window win ? win : SwingUtilities.getWindowAncestor(c);
            var target = w != null ? w : c;
            target.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
        });
    }

    // ---- Shell -------------------------------------------------------------

    public static boolean browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return false;
    }

    public static boolean open(Path path) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
                return true;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return false;
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

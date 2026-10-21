package com.postgresforall.app.ui;

import com.postgresforall.app.ui.diag.RoundedPanel;
import com.postgresforall.app.ui.theme.Icons;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A small notification in the bottom-right corner of a window ("Backup
 * finished"). Never takes focus, hides itself after five seconds (fading where
 * the platform supports it), and stacks when several are showing.
 */
public final class Toast {
    private Toast() { }

    private static final int MARGIN = 16;
    private static final int GAP = 8;
    private static final int WIDTH = 340;
    private static final List<JWindow> SHOWING = new ArrayList<>();

    /** Shows a toast over {@code owner}'s window. Safe from any thread. */
    public static void show(Component owner, String title, String message, boolean success) {
        Ui.later(() -> showNow(owner, title, message, success));
    }

    private static void showNow(Component owner, String title, String message, boolean success) {
        var parent = owner instanceof Window w ? w : owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        if (parent == null || !parent.isShowing()) return;

        var window = new JWindow(parent);
        window.setFocusableWindowState(false);
        window.setAlwaysOnTop(false);

        var card = new RoundedPanel(new BorderLayout(10, 0))
                .fill(() -> UIManager.getColor("Panel.background"))
                .outline(success ? Palette::bannerSuccessBorder : Palette::bannerErrorBorder)
                .accent(success ? Palette::success : Palette::danger);
        card.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 6));

        var icon = new JLabel(success
                ? Icons.tinted("circle-check", Icons.LARGE, Palette::success)
                : Icons.tinted("circle-x", Icons.LARGE, Palette::danger));
        icon.setVerticalAlignment(JLabel.TOP);
        card.add(icon, BorderLayout.WEST);

        var text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        var t = new JLabel(title);
        t.putClientProperty("FlatLaf.styleClass", "semibold");
        text.add(t);
        if (message != null && !message.isBlank()) {
            var m = new JLabel("<html><body style='width:" + (WIDTH - 110) + "px'>" + Ui.escapeHtml(message)
                    + "</body></html>");
            m.putClientProperty("FlatLaf.styleClass", "small muted");
            text.add(m);
        }
        card.add(text, BorderLayout.CENTER);

        var close = new JButton(Icons.get("x", Icons.SMALL));
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setFocusable(false);
        close.setToolTipText("Dismiss");
        close.addActionListener(e -> dismiss(window, parent));
        var closeBox = new JPanel(new BorderLayout());
        closeBox.setOpaque(false);
        closeBox.add(close, BorderLayout.NORTH);
        card.add(closeBox, BorderLayout.EAST);

        window.setContentPane(card);
        window.pack();
        window.setSize(WIDTH, window.getHeight());

        var device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (device.isWindowTranslucencySupported(WindowTranslucency.PERPIXEL_TRANSPARENT)) {
            window.setShape(new RoundRectangle2D.Float(0, 0, window.getWidth(), window.getHeight(), 12, 12));
        }

        SHOWING.add(window);
        layout(parent);
        window.setVisible(true);

        // Follow the owner when it moves or resizes.
        var follow = new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { layout(parent); }
            @Override public void componentResized(ComponentEvent e) { layout(parent); }
        };
        parent.addComponentListener(follow);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                parent.removeComponentListener(follow);
            }
        });

        var hide = new Timer(5000, e -> dismiss(window, parent));
        hide.setRepeats(false);
        hide.start();
    }

    /** Stacks the showing toasts upward from the owner's bottom-right corner. */
    private static void layout(Window parent) {
        var b = parent.getBounds();
        var y = b.y + b.height - MARGIN;
        for (var i = SHOWING.size() - 1; i >= 0; i--) {
            var w = SHOWING.get(i);
            if (w.getOwner() != parent) continue;
            y -= w.getHeight();
            w.setLocation(b.x + b.width - MARGIN - w.getWidth(), y);
            y -= GAP;
        }
    }

    private static void dismiss(JWindow window, Window parent) {
        if (!SHOWING.contains(window)) return;
        var device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (!device.isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT)) {
            close(window, parent);
            return;
        }
        var fade = new Timer(30, null);
        fade.addActionListener(e -> {
            var o = window.getOpacity() - 0.12f;
            if (o <= 0.05f) {
                fade.stop();
                close(window, parent);
            } else {
                window.setOpacity(o);
            }
        });
        fade.start();
    }

    private static void close(JWindow window, Window parent) {
        SHOWING.remove(window);
        window.dispose();
        layout(parent);
    }
}

package com.postgresforall.app.ui.diag;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Supplier;

/**
 * A rounded "card": optional fill, outline and left accent bar. Colours are
 * suppliers read at paint time, so a card follows a live light / dark switch.
 */
public class RoundedPanel extends JPanel {
    private static final int ARC = 10;

    private Supplier<Color> fill;
    private Supplier<Color> outline;
    private Supplier<Color> accent;

    public RoundedPanel(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
    }

    /** Background; null paints none (the parent shows through). */
    public RoundedPanel fill(Supplier<Color> c) {
        fill = c;
        repaint();
        return this;
    }

    public RoundedPanel outline(Supplier<Color> c) {
        outline = c;
        repaint();
        return this;
    }

    /** A 3px bar down the left edge (severity colour). */
    public RoundedPanel accent(Supplier<Color> c) {
        accent = c;
        var b = getBorder() == null ? null : getBorder().getBorderInsets(this);
        if (c != null && b != null && b.left < 13) {
            setBorder(BorderFactory.createEmptyBorder(b.top, 13, b.bottom, b.right));
        }
        repaint();
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        var g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var w = getWidth() - 1f;
            var h = getHeight() - 1f;
            var shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 0.5f, h - 0.5f, ARC, ARC);
            var bg = fill != null ? fill.get() : null;
            if (bg != null) {
                g2.setColor(bg);
                g2.fill(shape);
            }
            if (accent != null && accent.get() != null) {
                var clip = g2.getClip();
                g2.clip(shape);
                g2.setColor(accent.get());
                g2.fillRect(0, 0, 3, getHeight());
                g2.setClip(clip);
            }
            var line = outline != null ? outline.get() : null;
            if (line != null) {
                g2.setColor(line);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(shape);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    /** The theme's normal panel background, for cards that sit on a different surface. */
    public static Color panelBackground() {
        return UIManager.getColor("Panel.background");
    }
}

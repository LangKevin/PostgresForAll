package com.postgresforall.app.ui.diagram;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.theme.Fonts;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.Relationships.DiagramColumn;
import com.postgresforall.core.model.Relationships.DiagramTable;
import com.postgresforall.core.model.Relationships.Relationship;
import com.postgresforall.core.model.Relationships.RelationshipGraph;
import com.postgresforall.core.model.TableObjects.TableRef;

import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Paints a {@link RelationshipGraph} as table boxes joined by crow's-foot
 * lines, and handles the interaction: drag a box to move it, drag the
 * background to pan, Ctrl/⌘ + wheel to zoom, hover to highlight a table's
 * relationships, double-click a table to centre the diagram on it.
 */
public final class DiagramCanvas extends JComponent {
    /** Boxes with more columns show keys first, then the rest up to this many rows. */
    static final int MAX_ROWS = 16;
    private static final int PAD = 10;
    private static final int ROW_H = 22;
    private static final int HEADER_H = 30;
    private static final int STUB = 16;
    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 2.0;

    private static final FlatSVGIcon KEY = Icons.tinted("key-round", Icons.SMALL, Palette::caution);
    private static final FlatSVGIcon FK = Icons.tinted("link", Icons.SMALL, Palette::notice);

    private RelationshipGraph graph;
    private final Map<TableRef, Rectangle> boxes = new LinkedHashMap<>();
    private final Map<Relationship, Shape> edgeShapes = new LinkedHashMap<>();
    private boolean keysOnly;
    private double scale = 1.0;

    private TableRef hoverTable;
    private Relationship hoverEdge;
    private TableRef dragTable;
    private Point dragOffset;
    private Point panStart;
    private Point viewStart;

    private Consumer<TableRef> onOpen = t -> { };
    private Function<TableRef, JPopupMenu> menuFor = t -> null;

    public DiagramCanvas() {
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        var mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { onHover(e); }
            @Override public void mousePressed(MouseEvent e) { onPress(e); }
            @Override public void mouseDragged(MouseEvent e) { onDrag(e); }
            @Override public void mouseReleased(MouseEvent e) { onRelease(e); }
            @Override public void mouseExited(MouseEvent e) { setHover(null, null); }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    var t = tableAt(toModel(e.getPoint()));
                    if (t != null) onOpen.accept(t);
                }
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) { onWheel(e); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Double-click on a table. */
    public void onOpen(Consumer<TableRef> c) {
        onOpen = c;
    }

    /** Right-click menu for a table (null for none). */
    public void menuFor(Function<TableRef, JPopupMenu> f) {
        menuFor = f;
    }

    public RelationshipGraph graph() {
        return graph;
    }

    public void setGraph(RelationshipGraph g) {
        graph = g;
        hoverTable = null;
        hoverEdge = null;
        relayout();
    }

    public void setKeysOnly(boolean on) {
        keysOnly = on;
        relayout();
    }

    /** Throws away manual moves and lays the boxes out again. */
    public void relayout() {
        boxes.clear();
        if (graph != null) boxes.putAll(DiagramLayout.layout(graph, this::measure));
        geometryChanged();
    }

    /** Model-space box of a table (null if not shown). For tests and the tab's "centre" action. */
    public Rectangle boxOf(TableRef t) {
        var r = boxes.get(t);
        return r == null ? null : new Rectangle(r);
    }

    public double scale() {
        return scale;
    }

    public void setScale(double s) {
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
        revalidate();
        repaint();
    }

    public void zoom(double factor) {
        var vp = viewport();
        if (vp == null) {
            setScale(scale * factor);
            return;
        }
        var view = vp.getViewRect();
        var center = toModel(new Point(view.x + view.width / 2, view.y + view.height / 2));
        setScale(scale * factor);
        scrollModelPointToCenter(center);
    }

    /** Scales so the whole diagram fits the visible area (never above 100%). */
    public void fit() {
        var vp = viewport();
        if (vp == null || boxes.isEmpty()) return;
        var b = DiagramLayout.bounds(boxes);
        var s = Math.min((double) vp.getWidth() / b.width, (double) vp.getHeight() / b.height);
        setScale(Math.min(1.0, s));
        vp.setViewPosition(new Point(0, 0));
    }

    /**
     * Fits the diagram if that keeps the scale at or above {@code minScale};
     * otherwise uses {@code minScale} and scrolls {@code focus} into the middle.
     */
    public void overview(TableRef focus, double minScale) {
        var vp = viewport();
        if (vp == null || boxes.isEmpty()) return;
        var b = DiagramLayout.bounds(boxes);
        var s = Math.min((double) vp.getWidth() / b.width, (double) vp.getHeight() / b.height);
        setScale(Math.max(minScale, Math.min(1.0, s)));
        reveal(focus);
    }

    /** Scrolls a table's box to the middle of the visible area. */
    public void reveal(TableRef t) {
        var r = boxes.get(t);
        if (r != null) scrollModelPointToCenter(new Point2D.Double(r.getCenterX(), r.getCenterY()));
    }

    private void scrollModelPointToCenter(Point2D p) {
        var vp = viewport();
        if (vp == null) return;
        SwingUtilities.invokeLater(() -> {
            var ext = vp.getExtentSize();
            var size = getPreferredSize();
            var x = (int) Math.round(p.getX() * scale - ext.width / 2.0);
            var y = (int) Math.round(p.getY() * scale - ext.height / 2.0);
            x = Math.max(0, Math.min(x, size.width - ext.width));
            y = Math.max(0, Math.min(y, size.height - ext.height));
            vp.setViewPosition(new Point(x, y));
        });
    }

    private JViewport viewport() {
        return getParent() instanceof JViewport vp ? vp : null;
    }

    private void geometryChanged() {
        edgeShapes.clear();
        revalidate();
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Fonts can change with the theme: re-measure.
        if (graph != null) relayout();
    }

    @Override
    public Dimension getPreferredSize() {
        var b = DiagramLayout.bounds(boxes);
        return new Dimension((int) Math.ceil(b.width * scale), (int) Math.ceil(b.height * scale));
    }

    // ---- Measuring -------------------------------------------------------

    private static Font headerFont() {
        return Fonts.ui(0, true);
    }

    private static Font rowFont() {
        return Fonts.ui(-1, false);
    }

    private static Font typeFont() {
        return Fonts.mono(-2);
    }

    private static boolean isKey(DiagramColumn c) {
        return c.primaryKey() || c.foreignKey() || c.unique();
    }

    /** Columns drawn in a box: all of them, keys only, or keys plus the first others up to {@link #MAX_ROWS}. */
    List<DiagramColumn> visibleColumns(DiagramTable t) {
        var all = t.columns();
        if (keysOnly) return all.stream().filter(DiagramCanvas::isKey).toList();
        if (all.size() <= MAX_ROWS) return all;
        var keys = all.stream().filter(DiagramCanvas::isKey).count();
        var room = Math.max(0, MAX_ROWS - keys);
        var out = new ArrayList<DiagramColumn>();
        for (var c : all) {
            if (isKey(c)) {
                out.add(c);
            } else if (room > 0) {
                out.add(c);
                room--;
            }
        }
        return out;
    }

    private int hiddenCount(DiagramTable t) {
        return t.columns().size() - visibleColumns(t).size();
    }

    private Dimension measure(TableRef ref) {
        var t = graph.table(ref);
        var hfm = getFontMetrics(headerFont());
        var rfm = getFontMetrics(rowFont());
        var tfm = getFontMetrics(typeFont());
        var sfm = getFontMetrics(Fonts.ui(-2, false));
        var w = PAD + hfm.stringWidth(ref.name()) + 8 + sfm.stringWidth(headerDetail(t)) + PAD;
        var rows = visibleColumns(t);
        for (var c : rows) {
            w = Math.max(w, PAD + Icons.SMALL + 6 + rfm.stringWidth(c.name()) + 18 + tfm.stringWidth(c.dataType()) + PAD);
        }
        var extra = hiddenCount(t) > 0 || rows.isEmpty() ? 1 : 0;
        return new Dimension(Math.max(170, w), HEADER_H + (rows.size() + extra) * ROW_H + 6);
    }

    private static String headerDetail(DiagramTable t) {
        var parts = new ArrayList<String>();
        if (!"public".equals(t.ref().schema())) parts.add(t.ref().schema());
        if (t.estimatedRows() >= 0) parts.add(rows(t.estimatedRows()));
        return String.join(" · ", parts);
    }

    private static String rows(long n) {
        if (n < 1000) return n + " rows";
        if (n < 1_000_000) return String.format("%.1fk rows", n / 1000.0);
        return String.format("%.1fM rows", n / 1_000_000.0);
    }

    // ---- Edge geometry ---------------------------------------------------

    /** Endpoint of a relationship on a box side, at the row of its first column (or the header). */
    private Point anchor(TableRef t, List<String> cols, boolean rightSide) {
        var r = boxes.get(t);
        var vis = visibleColumns(graph.table(t));
        var idx = -1;
        if (!cols.isEmpty()) {
            for (var i = 0; i < vis.size(); i++) if (vis.get(i).name().equals(cols.get(0))) idx = i;
        }
        var y = idx >= 0 ? r.y + HEADER_H + idx * ROW_H + ROW_H / 2 : r.y + HEADER_H / 2;
        return new Point(rightSide ? r.x + r.width : r.x, y);
    }

    /** Line geometry for one relationship: start (many side), end (one side), and which way each leaves its box. */
    private record EdgeGeo(Point from, int fromDir, Point to, int toDir, Path2D path) { }

    private EdgeGeo geometry(Relationship rel) {
        var fr = boxes.get(rel.from());
        var tr = boxes.get(rel.to());
        boolean fromRight;
        boolean toRight;
        if (rel.selfReference()) {
            fromRight = true;
            toRight = true;
        } else if (fr.x > tr.x + tr.width) {
            fromRight = false;   // child to the right of its parent
            toRight = true;
        } else if (fr.x + fr.width < tr.x) {
            fromRight = true;
            toRight = false;
        } else {
            fromRight = true;    // same column: loop out on the right
            toRight = true;
        }
        var p0 = anchor(rel.from(), rel.fromColumns(), fromRight);
        var p3 = anchor(rel.to(), rel.toColumns(), toRight);
        int d0 = fromRight ? 1 : -1;
        int d3 = toRight ? 1 : -1;
        var s0 = new Point2D.Double(p0.x + d0 * STUB, p0.y);
        var s3 = new Point2D.Double(p3.x + d3 * STUB, p3.y);
        double k = d0 == d3 ? 40 + Math.abs(s3.y - s0.y) / 4 + Math.abs(s3.x - s0.x) / 2
                : Math.max(40, Math.abs(s3.x - s0.x) / 2);
        var path = new Path2D.Double();
        path.moveTo(p0.x, p0.y);
        path.lineTo(s0.x, s0.y);
        path.curveTo(s0.x + d0 * k, s0.y, s3.x + d3 * k, s3.y, s3.x, s3.y);
        path.lineTo(p3.x, p3.y);
        return new EdgeGeo(p0, d0, p3, d3, path);
    }

    private Shape edgeShape(Relationship rel) {
        return edgeShapes.computeIfAbsent(rel, r -> new BasicStroke(9).createStrokedShape(geometry(r).path()));
    }

    // ---- Painting --------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        var g = (Graphics2D) g0.create();
        try {
            g.setColor(UIManager.getColor("Panel.background"));
            g.fillRect(0, 0, getWidth(), getHeight());
            if (graph == null) return;
            paintDiagram(g);
        } finally {
            g.dispose();
        }
    }

    private void paintDiagram(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        var hints = (Map<?, ?>) Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints != null) g.addRenderingHints(hints);
        else g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Text keeps its measured width at any zoom.
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(scale, scale);

        // Lines first, so boxes sit on top; the highlighted ones last.
        var highlighted = new ArrayList<Relationship>();
        for (var rel : graph.relationships()) {
            if (!boxes.containsKey(rel.from()) || !boxes.containsKey(rel.to())) continue;
            if (isHighlighted(rel)) {
                highlighted.add(rel);
            } else {
                var faded = hoverTable != null || hoverEdge != null;
                paintEdge(g, rel, Palette.muted(), faded ? 0.35f : 1f, 1.4f);
            }
        }
        for (var rel : highlighted) paintEdge(g, rel, Palette.accent(), 1f, 2.2f);

        for (var t : graph.tables()) {
            if (boxes.containsKey(t.ref())) paintBox(g, t);
        }
    }

    private boolean isHighlighted(Relationship rel) {
        if (hoverEdge != null) return rel.equals(hoverEdge);
        return hoverTable != null && (rel.from().equals(hoverTable) || rel.to().equals(hoverTable));
    }

    private void paintEdge(Graphics2D g0, Relationship rel, Color color, float alpha, float width) {
        var g = (Graphics2D) g0.create();
        try {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.setColor(color);
            var geo = geometry(rel);
            g.setStroke(rel.validated() ? new BasicStroke(width)
                    : new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{6f, 4f}, 0f));
            g.draw(geo.path());
            g.setStroke(new BasicStroke(width));
            paintManyEnd(g, geo.from(), geo.fromDir(), rel.oneToOne());
            paintOneEnd(g, geo.to(), geo.toDir(), rel.optional());
        } finally {
            g.dispose();
        }
    }

    /** Referencing side: crow's foot (many), or a bar when the key is unique (one). */
    private static void paintManyEnd(Graphics2D g, Point p, int dir, boolean one) {
        if (one) {
            g.draw(new Line2D.Double(p.x + dir * 9, p.y - 6, p.x + dir * 9, p.y + 6));
            return;
        }
        var tip = p.x + dir * 12;
        g.draw(new Line2D.Double(tip, p.y, p.x, p.y - 6));
        g.draw(new Line2D.Double(tip, p.y, p.x, p.y + 6));
        g.draw(new Line2D.Double(tip, p.y, p.x, p.y));
    }

    /** Referenced side: two bars (exactly one), or bar + circle (zero or one) when the key columns allow NULL. */
    private void paintOneEnd(Graphics2D g, Point p, int dir, boolean optional) {
        g.draw(new Line2D.Double(p.x + dir * 6, p.y - 6, p.x + dir * 6, p.y + 6));
        if (optional) {
            var cx = p.x + dir * 13;
            var circle = new Ellipse2D.Double(cx - 3.5, p.y - 3.5, 7, 7);
            var c = g.getColor();
            g.setColor(UIManager.getColor("Panel.background"));
            g.fill(circle);
            g.setColor(c);
            g.draw(circle);
        } else {
            g.draw(new Line2D.Double(p.x + dir * 10, p.y - 6, p.x + dir * 10, p.y + 6));
        }
    }

    private void paintBox(Graphics2D g, DiagramTable t) {
        var r = boxes.get(t.ref());
        var focus = t.ref().equals(graph.focus());
        var hover = t.ref().equals(hoverTable);
        var arc = 10;

        g.setColor(UIManager.getColor("Table.background"));
        g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc);

        // Header strip, clipped to the rounded top.
        var clip = g.getClip();
        g.clipRect(r.x, r.y, r.width, HEADER_H);
        g.setColor(focus ? Palette.brand() : Palette.headerStrip());
        g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc);
        g.setClip(clip);

        var hf = headerFont();
        g.setFont(hf);
        var hfm = g.getFontMetrics();
        var base = r.y + (HEADER_H + hfm.getAscent() - hfm.getDescent()) / 2;
        g.setColor(focus ? Palette.chipText() : UIManager.getColor("Label.foreground"));
        g.drawString(t.ref().name(), r.x + PAD, base);
        var detail = headerDetail(t);
        if (!detail.isEmpty()) {
            g.setFont(Fonts.ui(-2, false));
            var sfm = g.getFontMetrics();
            g.setColor(focus ? Palette.chipText() : Palette.subtle());
            g.drawString(detail, r.x + r.width - PAD - sfm.stringWidth(detail), base);
        }

        var rows = visibleColumns(t);
        var rf = rowFont();
        var tf = typeFont();
        var y = r.y + HEADER_H;
        for (var c : rows) {
            g.setFont(c.primaryKey() ? rf.deriveFont(Font.BOLD) : rf);
            var fm = g.getFontMetrics();
            var textBase = y + (ROW_H + fm.getAscent() - fm.getDescent()) / 2;
            var icon = c.primaryKey() ? KEY : c.foreignKey() ? FK : null;
            if (icon != null) icon.paintIcon(this, g, r.x + PAD, y + (ROW_H - Icons.SMALL) / 2);
            var typeWidth = g.getFontMetrics(tf).stringWidth(c.dataType());
            var nameX = r.x + PAD + Icons.SMALL + 6;
            var typeX = r.x + r.width - PAD - typeWidth;
            var saved = g.getClip();
            g.clipRect(nameX, y, Math.max(0, typeX - 8 - nameX), ROW_H);
            g.setColor(UIManager.getColor("Label.foreground"));
            g.drawString(c.name(), nameX, textBase);
            g.setClip(saved);
            g.setFont(tf);
            g.setColor(Palette.subtle());
            g.drawString(c.dataType(), typeX, textBase);
            y += ROW_H;
        }
        var hidden = hiddenCount(t);
        if (hidden > 0 || rows.isEmpty()) {
            g.setFont(rf.deriveFont(Font.ITALIC));
            var fm = g.getFontMetrics();
            g.setColor(Palette.subtle());
            var text = rows.isEmpty() ? (keysOnly ? "(no key columns)" : "(no columns)")
                    : "… " + hidden + " more column" + (hidden == 1 ? "" : "s");
            g.drawString(text, r.x + PAD + Icons.SMALL + 6, y + (ROW_H + fm.getAscent() - fm.getDescent()) / 2);
        }

        g.setStroke(new BasicStroke(focus || hover ? 2f : 1f));
        g.setColor(focus || hover ? Palette.accent() : Palette.border());
        g.drawRoundRect(r.x, r.y, r.width, r.height, arc, arc);
    }

    /** The whole diagram as an image, at {@code imageScale} (2 = sharp on high-DPI screens). */
    public BufferedImage renderImage(double imageScale) {
        var b = DiagramLayout.bounds(boxes);
        var img = new BufferedImage(Math.max(1, (int) (b.width * imageScale)), Math.max(1, (int) (b.height * imageScale)),
                BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        var savedScale = scale;
        var savedHover = hoverTable;
        var savedEdge = hoverEdge;
        try {
            scale = imageScale;
            hoverTable = null;
            hoverEdge = null;
            g.setColor(UIManager.getColor("Panel.background"));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            if (graph != null) paintDiagram(g);
        } finally {
            scale = savedScale;
            hoverTable = savedHover;
            hoverEdge = savedEdge;
            g.dispose();
        }
        return img;
    }

    // ---- Interaction -----------------------------------------------------

    private Point2D toModel(Point p) {
        return new Point2D.Double(p.x / scale, p.y / scale);
    }

    TableRef tableAt(Point2D p) {
        if (graph == null) return null;
        // Topmost first: boxes paint in table order, so search backwards.
        var tables = graph.tables();
        for (var i = tables.size() - 1; i >= 0; i--) {
            var r = boxes.get(tables.get(i).ref());
            if (r != null && r.contains(p)) return tables.get(i).ref();
        }
        return null;
    }

    private Relationship edgeAt(Point2D p) {
        if (graph == null) return null;
        for (var rel : graph.relationships()) {
            if (boxes.containsKey(rel.from()) && boxes.containsKey(rel.to()) && edgeShape(rel).contains(p)) return rel;
        }
        return null;
    }

    private void setHover(TableRef t, Relationship e) {
        if (java.util.Objects.equals(t, hoverTable) && java.util.Objects.equals(e, hoverEdge)) return;
        hoverTable = t;
        hoverEdge = e;
        repaint();
    }

    private void onHover(MouseEvent e) {
        var p = toModel(e.getPoint());
        var t = tableAt(p);
        var edge = t == null ? edgeAt(p) : null;
        setHover(t, edge);
        setToolTipText(edge != null ? edgeTip(edge) : t != null ? tableTip(t, p) : null);
        setCursor(java.awt.Cursor.getPredefinedCursor(t != null ? java.awt.Cursor.MOVE_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String edgeTip(Relationship r) {
        var sb = new StringBuilder("<html><b>").append(esc(r.name())).append("</b><br>").append(esc(r.describe()));
        sb.append("<br>").append(r.oneToOne() ? "one-to-one" : "many-to-one")
                .append(r.optional() ? " · optional (nullable)" : " · required");
        if (!"NO ACTION".equals(r.onDelete())) sb.append("<br>ON DELETE ").append(r.onDelete());
        if (!"NO ACTION".equals(r.onUpdate())) sb.append("<br>ON UPDATE ").append(r.onUpdate());
        if (!r.validated()) sb.append("<br><i>NOT VALID (existing rows not checked)</i>");
        return sb.append("</html>").toString();
    }

    private String tableTip(TableRef ref, Point2D p) {
        var t = graph.table(ref);
        var r = boxes.get(ref);
        var row = (int) ((p.getY() - r.y - HEADER_H) / ROW_H);
        var vis = visibleColumns(t);
        if (p.getY() >= r.y + HEADER_H && row >= 0 && row < vis.size()) {
            var c = vis.get(row);
            var flags = new ArrayList<String>();
            if (c.primaryKey()) flags.add("primary key");
            else if (c.unique()) flags.add("unique");
            if (c.foreignKey()) flags.add("foreign key");
            flags.add(c.nullable() ? "nullable" : "NOT NULL");
            return "<html><b>" + esc(c.name()) + "</b> " + esc(c.dataType()) + "<br>" + String.join(" · ", flags) + "</html>";
        }
        var sb = new StringBuilder("<html><b>").append(esc(ref.toString())).append("</b>");
        if (t.comment() != null && !t.comment().isBlank()) sb.append("<br>").append(esc(t.comment()));
        sb.append("<br>").append(t.columns().size()).append(" columns");
        if (t.estimatedRows() >= 0) sb.append(" · ~").append(rows(t.estimatedRows()));
        if (!ref.equals(graph.focus())) sb.append("<br><i>Double-click to centre the diagram on this table</i>");
        return sb.append("</html>").toString();
    }

    private void onPress(MouseEvent e) {
        if (maybeMenu(e)) return;
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        var p = toModel(e.getPoint());
        var t = tableAt(p);
        if (t != null) {
            dragTable = t;
            var r = boxes.get(t);
            dragOffset = new Point((int) (p.getX() - r.x), (int) (p.getY() - r.y));
        } else if (viewport() != null) {
            panStart = e.getLocationOnScreen();
            viewStart = viewport().getViewPosition();
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }
    }

    private void onDrag(MouseEvent e) {
        if (dragTable != null) {
            var p = toModel(e.getPoint());
            var r = boxes.get(dragTable);
            r.setLocation(Math.max(DiagramLayout.MARGIN / 2, (int) p.getX() - dragOffset.x),
                    Math.max(DiagramLayout.MARGIN / 2, (int) p.getY() - dragOffset.y));
            geometryChanged();
            scrollRectToVisible(new Rectangle(e.getX() - 10, e.getY() - 10, 20, 20));
        } else if (panStart != null) {
            var vp = viewport();
            var now = e.getLocationOnScreen();
            var size = getPreferredSize();
            var ext = vp.getExtentSize();
            var x = Math.max(0, Math.min(viewStart.x - (now.x - panStart.x), size.width - ext.width));
            var y = Math.max(0, Math.min(viewStart.y - (now.y - panStart.y), size.height - ext.height));
            vp.setViewPosition(new Point(x, y));
        }
    }

    private void onRelease(MouseEvent e) {
        dragTable = null;
        if (panStart != null) setCursor(java.awt.Cursor.getDefaultCursor());
        panStart = null;
        maybeMenu(e);
    }

    private boolean maybeMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return false;
        var t = tableAt(toModel(e.getPoint()));
        if (t == null) return false;
        var menu = menuFor.apply(t);
        if (menu != null) menu.show(this, e.getX(), e.getY());
        return true;
    }

    private void onWheel(MouseWheelEvent e) {
        if (e.isControlDown() || e.isMetaDown()) {
            var before = toModel(e.getPoint());
            var vp = viewport();
            var offset = vp == null ? new Point() : new Point(e.getX() - vp.getViewPosition().x, e.getY() - vp.getViewPosition().y);
            setScale(scale * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1));
            if (vp != null) {
                // Keep the point under the cursor where it was.
                SwingUtilities.invokeLater(() -> {
                    var size = getPreferredSize();
                    var ext = vp.getExtentSize();
                    var x = (int) Math.round(before.getX() * scale) - offset.x;
                    var y = (int) Math.round(before.getY() * scale) - offset.y;
                    vp.setViewPosition(new Point(Math.max(0, Math.min(x, size.width - ext.width)),
                            Math.max(0, Math.min(y, size.height - ext.height))));
                });
            }
            return;
        }
        // Plain wheel: let the scroll pane scroll.
        var sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        if (sp != null) sp.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, sp));
    }
}

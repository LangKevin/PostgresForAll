package com.postgresforall.app.ui.diagram;

import com.formdev.flatlaf.FlatClientProperties;
import com.postgresforall.app.AppSettings;
import com.postgresforall.app.ui.FileDialogs;
import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Toast;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.Relationships.RelationshipGraph;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.service.ConnectionErrors;
import net.miginfocom.swing.MigLayout;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.function.Consumer;

/**
 * Document tab showing a table's foreign-key neighbourhood: the tables it
 * references, the tables referencing it, and (with more hops) their neighbours.
 */
public final class RelationshipDiagramTab extends JPanel {

    /** Loads the graph around a table. Called off the EDT. */
    @FunctionalInterface
    public interface Loader {
        RelationshipGraph load(TableRef focus, int depth) throws Exception;
    }

    private static final int AUTO_KEYS_ONLY_TABLES = 10;
    private static final String[] DEPTHS = {"1 hop", "2 hops", "3 hops"};

    private final String database;
    private final Loader loader;
    private final Consumer<TableRef> showInExplorer;
    private final DiagramCanvas canvas = new DiagramCanvas();
    private final JScrollPane scroll = new JScrollPane(canvas);
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final JComboBox<String> depth = new JComboBox<>(DEPTHS);
    private final JToggleButton keysOnly = new JToggleButton("Keys only", Icons.get("key-round", Icons.SMALL));
    private final JLabel status = new JLabel();
    private TableRef focus;
    private Runnable onTitleChange = () -> { };
    private long loadGeneration;
    /** Until the user touches "Keys only", big diagrams switch it on by themselves. */
    private boolean keysOnlyChosen;

    public RelationshipDiagramTab(TableRef focus, String database, Loader loader, Consumer<TableRef> showInExplorer) {
        super(new BorderLayout());
        this.focus = focus;
        this.database = database;
        this.loader = loader;
        this.showInExplorer = showInExplorer;

        var bar = new JPanel(new MigLayout("insets 6 10 6 10, gapx 6", "[][][]12[][][][]12[][][]push[]"));
        bar.add(new JLabel("Depth"));
        depth.setToolTipText("How many foreign-key hops to follow from the centre table");
        depth.addActionListener(e -> reload());
        bar.add(depth);
        keysOnly.setToolTipText("Show only key columns (primary, unique and foreign keys)");
        keysOnly.addActionListener(e -> {
            keysOnlyChosen = true;
            canvas.setKeysOnly(keysOnly.isSelected());
        });
        bar.add(keysOnly);
        bar.add(tool("zoom-out", "Zoom out (Ctrl + wheel)", () -> canvas.zoom(1 / 1.2)));
        bar.add(tool("zoom-in", "Zoom in (Ctrl + wheel)", () -> canvas.zoom(1.2)));
        bar.add(tool("maximize", "Fit to window", canvas::fit));
        bar.add(tool("layers", "Re-arrange (undo moved boxes)", () -> {
            canvas.relayout();
            canvas.reveal(this.focus);
        }));
        bar.add(tool("refresh-cw", "Reload from the database", this::reload));
        bar.add(tool("download", "Export as PNG…", this::exportPng));
        bar.add(tool("database", "Show the centre table in Object Explorer", () -> showInExplorer.accept(this.focus)));
        status.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        bar.add(status);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.border()));

        var legend = new JLabel("Crow's foot = many  ·  bars = exactly one  ·  circle = optional  ·  dashed = NOT VALID"
                + "  ·  double-click a table to centre on it");
        legend.setToolTipText("<html>Each line is a foreign key, from the referencing table (crow's foot: many rows) to the"
                + "<br>referenced table (two bars: exactly one row; bar and circle: zero or one, the key allows NULL)."
                + "<br>Drag boxes to move them, drag the background to pan, Ctrl + wheel to zoom.</html>");
        legend.putClientProperty(FlatClientProperties.STYLE_CLASS, "subtle");
        legend.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        message.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        center.add(message, "message");
        center.add(scroll, "diagram");

        add(bar, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(legend, BorderLayout.SOUTH);

        canvas.onOpen(t -> {
            if (!t.equals(this.focus)) recenter(t);
        });
        canvas.menuFor(this::tableMenu);
        reload();
    }

    private static JButton tool(String icon, String tip, Runnable action) {
        var b = new JButton(Icons.get(icon, Icons.NORMAL));
        b.setToolTipText(tip);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.addActionListener(e -> action.run());
        return b;
    }

    public TableRef focus() {
        return focus;
    }

    public String database() {
        return database;
    }

    public String title() {
        return "Diagram — " + focus.name();
    }

    public DiagramCanvas canvas() {
        return canvas;
    }

    /** Called (on the EDT) when the centre table, and so the title, changes. */
    public void onTitleChange(Runnable r) {
        onTitleChange = r;
    }

    /** Makes {@code t} the centre table and reloads. */
    public void recenter(TableRef t) {
        focus = t;
        onTitleChange.run();
        reload();
    }

    private JPopupMenu tableMenu(TableRef t) {
        var menu = new JPopupMenu();
        var center = new JMenuItem("Centre diagram on " + t.name());
        center.setEnabled(!t.equals(focus));
        center.addActionListener(e -> recenter(t));
        menu.add(center);
        var show = new JMenuItem("Show in Object Explorer", Icons.get("database", Icons.SMALL));
        show.addActionListener(e -> showInExplorer.accept(t));
        menu.add(show);
        return menu;
    }

    private void reload() {
        var gen = ++loadGeneration;
        var target = focus;
        var hops = depth.getSelectedIndex() + 1;
        message.setText("Loading relationships of " + target + "…");
        cards.show(center, "message");
        status.setText("");
        Ui.bgQuiet(() -> {
            RelationshipGraph g;
            try {
                g = loader.load(target, hops);
            } catch (Exception ex) {
                Ui.edt(() -> {
                    if (gen != loadGeneration) return;
                    message.setText("Couldn't load relationships: " + ConnectionErrors.message(ex));
                });
                return;
            }
            Ui.edt(() -> {
                if (gen != loadGeneration) return;
                var tables = g.tables().size();
                if (!keysOnlyChosen) {
                    keysOnly.setSelected(tables > AUTO_KEYS_ONLY_TABLES);
                    canvas.setKeysOnly(keysOnly.isSelected());
                }
                canvas.setGraph(g);
                var rels = g.relationships().size();
                var text = tables + (tables == 1 ? " table" : " tables") + " · " + rels
                        + (rels == 1 ? " relationship" : " relationships") + " · " + database;
                if (g.truncated()) text += " · limited to " + tables + " tables, reduce the depth to see the closest ones";
                status.setText(text);
                status.putClientProperty(FlatClientProperties.STYLE_CLASS, g.truncated() ? "danger" : "muted");
                if (rels == 0) {
                    message.setText(target + " has no foreign keys, and none reference it.");
                    cards.show(center, "message");
                    return;
                }
                cards.show(center, "diagram");
                // Start with an overview: fit while it stays readable, else zoom out part way and centre.
                center.validate();
                canvas.overview(target, 0.55);
            });
        });
    }

    private void exportPng() {
        if (canvas.graph() == null) return;
        Ui.bg(this, () -> {
            var path = FileDialogs.save(this, "Export diagram", AppSettings.folderOr(),
                    focus.name() + "-relationships.png", "PNG image", "png");
            if (path == null) return;
            var img = Ui.call(() -> canvas.renderImage(2.0));
            ImageIO.write(img, "png", path.toFile());
            Ui.edt(() -> Toast.show(this, "Diagram exported", path.getFileName().toString(), true));
        });
    }
}

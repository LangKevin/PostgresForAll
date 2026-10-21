package com.postgresforall.app.ui.explorer;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.explorer.ExplorerNode.Kind;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.SchemaNodes.ColumnInfo;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.IndexDetail;
import com.postgresforall.core.model.SchemaNodes.DatabaseInfo;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.TreeCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.Locale;

/**
 * One tree row: kind icon, name, dimmed detail. Plain labels (no HTML), so
 * rows render fast and long details simply end at the panel edge. While a
 * filter is active, matching names are bold in the accent colour and the rest
 * are dimmed.
 */
final class ExplorerRenderer extends JPanel implements TreeCellRenderer {
    private final JLabel icon = new JLabel();
    private final JLabel name = new JLabel();
    private final JLabel detail = new JLabel();
    private String filter = "";

    ExplorerRenderer() {
        // A row never wraps: icon, name and detail stay on one line (overflow is clipped).
        setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS));
        setOpaque(false);
        icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        detail.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        add(icon);
        add(name);
        add(detail);
    }

    void setFilter(String f) {
        filter = f == null ? "" : f.trim().toLowerCase(Locale.ROOT);
    }

    static boolean matches(ExplorerNode n, String lowerFilter) {
        return !lowerFilter.isEmpty() && n.name.toLowerCase(Locale.ROOT).contains(lowerFilter);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                  boolean leaf, int row, boolean hasFocus) {
        if (!(value instanceof ExplorerNode n)) {
            name.setText(String.valueOf(value));
            detail.setText("");
            icon.setIcon(null);
            return this;
        }
        // Unfocused trees use FlatLaf's quieter inactive selection (dark text on grey in light themes).
        var selFg = UIManager.getColor(tree.hasFocus() ? "Tree.selectionForeground" : "Tree.selectionInactiveForeground");
        if (selFg == null) selFg = UIManager.getColor("Tree.selectionForeground");
        var normalFg = UIManager.getColor("Tree.foreground");
        var filtering = !filter.isEmpty();
        var hit = matches(n, filter);

        icon.setIcon(iconFor(n));
        name.setText(n.name);
        name.setFont(nameFont(tree.getFont(), n, hit));
        Color nameFg = n.foreground != null ? n.foreground : normalFg;
        if (filtering) nameFg = hit ? Palette.accent() : Palette.disabled();
        name.setForeground(sel ? selFg : nameFg);

        detail.setText(n.detail == null ? "" : n.detail);
        detail.setFont(tree.getFont());
        detail.setForeground(sel ? selFg : Palette.subtle());
        detail.setVisible(n.detail != null && !n.detail.isEmpty());
        setToolTipText(n.toolTip);
        return this;
    }

    private static Font nameFont(Font base, ExplorerNode n, boolean hit) {
        var bold = hit || switch (n.kind) {
            case SERVER, DATABASES_FOLDER, SECURITY_FOLDER -> true;
            case DATABASE -> n.name.endsWith("(connected)");
            default -> false;
        };
        var italic = n.kind == Kind.LOADING || n.kind == Kind.PLACEHOLDER;
        return base.deriveFont((bold ? Font.BOLD : Font.PLAIN) | (italic ? Font.ITALIC : Font.PLAIN));
    }

    // ---- Icons --------------------------------------------------------------

    private static final int SIZE = Icons.NORMAL;
    private static final FlatSVGIcon FOLDER = Icons.get("folder", SIZE);
    private static final FlatSVGIcon SCHEMA = Icons.get("layers", SIZE);
    private static final FlatSVGIcon TABLE = Icons.tinted("table", SIZE, Palette::notice);
    private static final FlatSVGIcon VIEW = Icons.get("eye", SIZE);
    private static final FlatSVGIcon FUNCTION = Icons.get("square-function", SIZE);
    private static final FlatSVGIcon COLUMN = Icons.get("columns-3", SIZE);
    private static final FlatSVGIcon KEY = Icons.tinted("key-round", SIZE, Palette::caution);
    private static final FlatSVGIcon SHIELD = Icons.get("shield", SIZE);
    private static final FlatSVGIcon USERS = Icons.get("users", SIZE);
    private static final FlatSVGIcon USER = Icons.get("user", SIZE);
    private static final FlatSVGIcon LOADING = Icons.get("loader", SIZE);
    private static final FlatSVGIcon INFO = Icons.get("info", SIZE);
    private static final FlatSVGIcon ERROR = Icons.tinted("circle-alert", SIZE, Palette::danger);
    private static final FlatSVGIcon SERVER_ON = Icons.tinted("server", SIZE, Palette::success);
    private static final FlatSVGIcon SERVER_FAILED = Icons.tinted("server", SIZE, Palette::danger);
    private static final FlatSVGIcon SERVER_OFF = Icons.tinted("server", SIZE, Palette::disabled);
    private static final FlatSVGIcon DB = Icons.get("database", SIZE);
    private static final FlatSVGIcon DB_LOGIN = Icons.tinted("database", SIZE, Palette::accent);
    private static final FlatSVGIcon DB_OFFLINE = Icons.tinted("lock", SIZE, Palette::disabled);
    private static final FlatSVGIcon FK = Icons.tinted("link", SIZE, Palette::notice);
    private static final FlatSVGIcon UNIQUE_ICON = Icons.get("square-check", SIZE);
    private static final FlatSVGIcon CHECK_ICON = Icons.get("list-checks", SIZE);
    private static final FlatSVGIcon EXCLUSION_ICON = Icons.get("ban", SIZE);
    private static final FlatSVGIcon INDEX_ICON = Icons.get("list-ordered", SIZE);
    private static final FlatSVGIcon INDEX_INVALID = Icons.tinted("list-ordered", SIZE, Palette::danger);
    private static final FlatSVGIcon TRIGGER_ON = Icons.tinted("zap", SIZE, Palette::caution);
    private static final FlatSVGIcon TRIGGER_OFF = Icons.tinted("zap-off", SIZE, Palette::disabled);
    private static final FlatSVGIcon REFERENCED_BY = Icons.get("corner-up-left", SIZE);

    private static Icon iconFor(ExplorerNode n) {
        return switch (n.kind) {
            case SERVER -> n.server == null ? SERVER_OFF : switch (n.server.state()) {
                case CONNECTED -> n.foreground != null ? SERVER_FAILED : SERVER_ON; // red = couldn't list databases
                case FAILED -> SERVER_FAILED;
                case CONNECTING, DISCONNECTED -> SERVER_OFF;
            };
            case DATABASE -> n.payload instanceof DatabaseInfo d && !d.allowConnections() ? DB_OFFLINE
                    : n.name.endsWith("(connected)") ? DB_LOGIN : DB;
            case DATABASES_FOLDER, SCHEMAS_FOLDER, TABLES_FOLDER, VIEWS_FOLDER, FUNCTIONS_FOLDER, COLUMNS_FOLDER,
                 KEYS_FOLDER, CONSTRAINTS_FOLDER, INDEXES_FOLDER, TRIGGERS_FOLDER -> FOLDER;
            case REFERENCED_BY_FOLDER -> REFERENCED_BY;
            case PRIMARY_KEY -> KEY;
            case FOREIGN_KEY, REFERENCING_FK -> FK;
            case UNIQUE -> UNIQUE_ICON;
            case CHECK -> CHECK_ICON;
            case EXCLUSION -> EXCLUSION_ICON;
            case INDEX -> n.payload instanceof IndexDetail ix && !ix.valid() ? INDEX_INVALID : INDEX_ICON;
            case TRIGGER -> n.foreground != null ? TRIGGER_OFF : TRIGGER_ON;
            case SCHEMA -> SCHEMA;
            case TABLE -> TABLE;
            case VIEW -> VIEW;
            case FUNCTION -> FUNCTION;
            case COLUMN -> n.payload instanceof ColumnInfo c && c.isPrimaryKey()
                    || n.payload instanceof ColumnDetail d && d.primaryKey() ? KEY : COLUMN;
            case SECURITY_FOLDER -> SHIELD;
            case ROLES_FOLDER -> USERS;
            case ROLE -> USER;
            case LOADING -> LOADING;
            case PLACEHOLDER -> n.name.startsWith("(error)") ? ERROR : INFO;
            case ROOT -> null;
        };
    }

}

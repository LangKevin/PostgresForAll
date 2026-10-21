package com.postgresforall.app.ui;

import com.postgresforall.app.ui.design.ChangeDialog;
import com.postgresforall.app.ui.design.CheckDialog;
import com.postgresforall.app.ui.design.ColumnDialog;
import com.postgresforall.app.ui.design.DropDialog;
import com.postgresforall.app.ui.design.ExclusionDialog;
import com.postgresforall.app.ui.design.ForeignKeyDialog;
import com.postgresforall.app.ui.design.IndexDialog;
import com.postgresforall.app.ui.design.KeyDialog;
import com.postgresforall.app.ui.design.RenameDialog;
import com.postgresforall.app.ui.design.SqlConfirmDialog;
import com.postgresforall.app.ui.design.TriggerDialog;
import com.postgresforall.app.ui.dialogs.TablePropertiesDialog;
import com.postgresforall.app.ui.explorer.ExplorerNode;
import com.postgresforall.app.ui.explorer.ExplorerNode.Kind;
import com.postgresforall.app.ui.explorer.ObjectExplorer;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.OperationEvent;
import com.postgresforall.core.model.OperationEvent.Severity;
import com.postgresforall.core.model.SchemaNodes.TableInfo;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.ConstraintDetail;
import com.postgresforall.core.model.TableObjects.ConstraintType;
import com.postgresforall.core.model.TableObjects.Dependency;
import com.postgresforall.core.model.TableObjects.IndexDetail;
import com.postgresforall.core.model.TableObjects.ReferencingFk;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.model.TableObjects.TriggerDetail;
import com.postgresforall.core.service.ConnectionErrors;
import com.postgresforall.core.service.ConnectionManager;
import com.postgresforall.core.service.DdlApplier;
import com.postgresforall.core.service.DdlBuilder;
import com.postgresforall.core.service.DependencyInspector;
import com.postgresforall.core.service.ServerSession;
import com.postgresforall.core.service.TableInspector;
import com.postgresforall.core.service.TablePropertiesInspector;
import net.miginfocom.swing.MigLayout;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Right-click structure editing for tables: columns, keys, constraints,
 * indexes and triggers. Every change goes through a {@link ChangeDialog}
 * (SQL preview + locks), then runs via {@link DdlApplier} in the Output panel
 * and reloads the table's node.
 */
final class TableDesignActions {

    /** Opens SQL in a new query tab against a database. Off the EDT. */
    @FunctionalInterface
    interface QueryOpener {
        void open(String sql, ServerSession server, String database) throws Exception;
    }

    private final Component owner;
    private final ObjectExplorer explorer;
    private final OperationOutputPanel output;
    private final Runnable showOutput;
    private final QueryOpener openQuery;
    private final java.util.function.Consumer<ExplorerNode> openDiagram;

    TableDesignActions(Component owner, ObjectExplorer explorer, OperationOutputPanel output, Runnable showOutput,
                       QueryOpener openQuery, java.util.function.Consumer<ExplorerNode> openDiagram) {
        this.owner = owner;
        this.explorer = explorer;
        this.output = output;
        this.showOutput = showOutput;
        this.openQuery = openQuery;
        this.openDiagram = openDiagram;
    }

    /** True for the node kinds {@link #populate} handles. */
    static boolean handles(Kind kind) {
        return switch (kind) {
            case TABLE, COLUMNS_FOLDER, COLUMN, KEYS_FOLDER, PRIMARY_KEY, FOREIGN_KEY, CONSTRAINTS_FOLDER, UNIQUE, CHECK,
                 EXCLUSION, INDEXES_FOLDER, INDEX, TRIGGERS_FOLDER, TRIGGER, REFERENCED_BY_FOLDER, REFERENCING_FK -> true;
            default -> false;
        };
    }

    /** Where an action runs: the table, its tree node, and a pool for its database. Built off the EDT. */
    private record Ctx(ExplorerNode tableNode, TableRef table, ServerSession server, String db, ConnectionManager mgr,
                       TableInspector ti) {
        String s() { return table.schema(); }
        String t() { return table.name(); }
    }

    private static ExplorerNode tableNodeOf(ExplorerNode n) {
        while (n != null && n.kind != Kind.TABLE) n = n.getParent() instanceof ExplorerNode p ? p : null;
        return n;
    }

    /** Fills {@code menu} for a table or one of its structure nodes. Mutating items grey out if the login can't alter it. */
    void populate(JPopupMenu menu, ExplorerNode node, Runnable scriptSelect) {
        var tableNode = tableNodeOf(node);
        if (tableNode == null || !(tableNode.payload instanceof TableInfo tb) || node.server == null) return;
        var table = new TableRef(tb.schema(), tb.name());
        var mutating = new ArrayList<JMenuItem>();

        switch (node.kind) {
            case TABLE -> {
                // Structure editing lives under Design ▸, keeping the top level short.
                var design = new JMenu("Design");
                design.setIcon(Icons.get("wrench", Icons.SMALL));
                mutating.add(add(design, "New column…", "plus", () -> newColumn(node)));
                mutating.add(add(design, "New index…", "list-ordered", () -> newIndex(node)));
                mutating.add(add(design, "New foreign key…", "link", () -> newForeignKey(node)));
                mutating.add(add(design, "New primary key…", "key-round", () -> newKey(node, true)));
                mutating.add(add(design, "New unique constraint…", "square-check", () -> newKey(node, false)));
                mutating.add(add(design, "New check constraint…", "list-checks", () -> newCheck(node)));
                mutating.add(add(design, "New exclusion constraint…", "ban", () -> newExclusion(node)));
                mutating.add(add(design, "New trigger…", "zap", () -> newTrigger(node)));
                design.addSeparator();
                add(design, "View relationship diagram", "network", () -> openDiagram.accept(tableNode));
                add(design, "View dependencies…", null, () -> viewDependencies(node));
                design.addSeparator();
                mutating.add(add(design, "Rename table…", "pencil", () -> renameTable(node)));
                var truncate = add(design, "Truncate table…", null, () -> truncateTable(node));
                truncate.setForeground(Palette.danger());
                mutating.add(truncate);
                var drop = add(design, "Drop table…", "trash-2", () -> dropTable(node));
                drop.setForeground(Palette.danger());
                mutating.add(drop);
                menu.add(design);
                menu.addSeparator();
                add(menu, "Script as SELECT (LIMIT 1000)", null, scriptSelect);
                add(menu, "Script as CREATE", null, () -> scriptCreate(node));
            }
            case COLUMNS_FOLDER -> mutating.add(add(menu, "New column…", "plus", () -> newColumn(node)));
            case COLUMN -> {
                var c = (ColumnDetail) node.payload;
                mutating.add(add(menu, "Edit column…", "pencil", () -> editColumn(node, c)));
                mutating.add(add(menu, "Rename column…", null, () -> rename(node, "column", c.name(), Kind.COLUMNS_FOLDER,
                        n -> DdlBuilder.renameColumn(table.schema(), table.name(), c.name(), n))));
                mutating.add(dropItem(menu, "Drop column…", () -> dropColumn(node, c)));
            }
            case KEYS_FOLDER -> {
                mutating.add(add(menu, "New primary key…", "key-round", () -> newKey(node, true)));
                mutating.add(add(menu, "New foreign key…", "link", () -> newForeignKey(node)));
                add(menu, "View relationship diagram", "network", () -> openDiagram.accept(tableNode));
            }
            case CONSTRAINTS_FOLDER -> {
                mutating.add(add(menu, "New unique constraint…", "square-check", () -> newKey(node, false)));
                mutating.add(add(menu, "New check constraint…", "list-checks", () -> newCheck(node)));
                mutating.add(add(menu, "New exclusion constraint…", "ban", () -> newExclusion(node)));
            }
            case PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK, EXCLUSION -> {
                var c = (ConstraintDetail) node.payload;
                var folder = node.kind == Kind.PRIMARY_KEY || node.kind == Kind.FOREIGN_KEY ? Kind.KEYS_FOLDER : Kind.CONSTRAINTS_FOLDER;
                mutating.add(add(menu, "Edit…", "pencil", () -> editConstraint(node, c)));
                mutating.add(add(menu, "Rename…", null, () -> rename(node, "constraint", c.name(), folder,
                        n -> DdlBuilder.renameConstraint(table.schema(), table.name(), c.name(), n))));
                if (!c.validated()) {
                    mutating.add(add(menu, "Validate", "circle-check", () -> confirm(node, "Validate constraint",
                            "Validate " + c.name(),
                            "Checks every existing row. Takes a SHARE UPDATE EXCLUSIVE lock, so reads and writes continue.",
                            List.of(DdlBuilder.validateConstraint(table.schema(), table.name(), c.name())), folder)));
                }
                if (c.type() == ConstraintType.FOREIGN_KEY) {
                    add(menu, "Go to referenced table", "corner-up-left",
                            () -> explorer.revealTable(node, c.refSchema(), c.refTable()));
                }
                mutating.add(dropItem(menu, "Drop…", () -> dropConstraint(node, table, c.name(), folder)));
            }
            case INDEXES_FOLDER -> mutating.add(add(menu, "New index…", "list-ordered", () -> newIndex(node)));
            case INDEX -> {
                var ix = (IndexDetail) node.payload;
                var backsConstraint = ix.constraintName() != null;
                var edit = add(menu, "Edit index…", "pencil", () -> editIndex(node, ix));
                mutating.add(edit);
                mutating.add(add(menu, "Rename index…", null, () -> rename(node, "index", ix.name(), Kind.INDEXES_FOLDER,
                        n -> DdlBuilder.renameIndex(table.schema(), table.name(), ix.name(), n))));
                mutating.add(add(menu, "Reindex…", "refresh-cw", () -> reindex(node, ix)));
                var drop = dropItem(menu, "Drop index…", () -> dropIndex(node, ix));
                mutating.add(drop);
                if (backsConstraint) {
                    for (var mi : List.of(edit, drop)) {
                        mi.setEnabled(false);
                        mi.setToolTipText("Backs constraint " + ix.constraintName() + " — change or drop the constraint instead.");
                    }
                }
            }
            case TRIGGERS_FOLDER -> mutating.add(add(menu, "New trigger…", "zap", () -> newTrigger(node)));
            case TRIGGER -> {
                var tr = (TriggerDetail) node.payload;
                mutating.add(add(menu, "Edit trigger…", "pencil", () -> editTrigger(node, tr)));
                mutating.add(add(menu, "Rename trigger…", null, () -> rename(node, "trigger", tr.name(), Kind.TRIGGERS_FOLDER,
                        n -> DdlBuilder.renameTrigger(table.schema(), table.name(), tr.name(), n))));
                mutating.add(add(menu, tr.enabled() ? "Disable trigger" : "Enable trigger", tr.enabled() ? "zap-off" : "zap",
                        () -> confirm(node, (tr.enabled() ? "Disable" : "Enable") + " trigger",
                                (tr.enabled() ? "Disable " : "Enable ") + tr.name(), null,
                                List.of(DdlBuilder.setTriggerEnabled(table.schema(), table.name(), tr.name(), !tr.enabled())),
                                Kind.TRIGGERS_FOLDER)));
                mutating.add(dropItem(menu, "Drop trigger…", () -> drop(node, "trigger", tr.name(), List.of(),
                        cascade -> DdlBuilder.dropTrigger(table.schema(), table.name(), tr.name()), Kind.TRIGGERS_FOLDER)));
            }
            case REFERENCED_BY_FOLDER -> {
                add(menu, "View relationship diagram", "network", () -> openDiagram.accept(tableNode));
            }
            case REFERENCING_FK -> {
                var r = (ReferencingFk) node.payload;
                add(menu, "Go to " + r.schema() + "." + r.table(), "corner-up-left",
                        () -> explorer.revealTable(node, r.schema(), r.table()));
                mutating.add(dropItem(menu, "Drop this foreign key…",
                        () -> dropConstraint(node, new TableRef(r.schema(), r.table()), r.constraintName(),
                                Kind.REFERENCED_BY_FOLDER)));
            }
            default -> { }
        }
        if (node.kind != Kind.TABLE) {
            menu.addSeparator();
            add(menu, "Refresh", "refresh-cw", () -> explorer.refreshNode(tableNode));
        } else {
            menu.addSeparator();
            add(menu, "Refresh", "refresh-cw", () -> explorer.refreshNode(node));
            menu.addSeparator();
            add(menu, "Properties", "info", () -> showProperties(node));
        }
        disableUnlessAlterable(node, table, mutating);
    }

    /** Adds an item to a popup menu or a submenu. */
    private static JMenuItem add(JComponent menu, String text, String icon, Runnable action) {
        var mi = new JMenuItem(text);
        if (icon != null) mi.setIcon(Icons.get(icon, Icons.SMALL));
        mi.addActionListener(e -> action.run());
        menu.add(mi);
        return mi;
    }

    private static JMenuItem dropItem(JComponent menu, String text, Runnable action) {
        var mi = add(menu, text, "trash-2", action);
        mi.setForeground(Palette.danger());
        return mi;
    }

    /** Only the owner (or a member of the owning role, or a superuser) can ALTER a table. Checked after the menu opens. */
    private void disableUnlessAlterable(ExplorerNode node, TableRef table, List<JMenuItem> items) {
        if (items.isEmpty()) return;
        var server = node.server;
        var db = node.databaseName;
        Ui.bgQuiet(() -> {
            var mgr = explorer.managerFor(server, db);
            if (mgr == null) return;
            var summary = new TableInspector(mgr).summary(table.schema(), table.name());
            if (summary.canAlter()) return;
            Ui.edt(() -> {
                for (var mi : items) {
                    mi.setEnabled(false);
                    mi.setToolTipText("Only the owner of " + table + " (" + summary.owner() + ") can change it.");
                }
            });
        });
    }

    // ---- Plumbing --------------------------------------------------------

    @FunctionalInterface
    private interface CtxWork {
        void run(Ctx c) throws Exception;
    }

    /** Resolves the context off the EDT, then runs {@code work} there too. */
    private void withCtx(ExplorerNode node, CtxWork work) {
        var tableNode = tableNodeOf(node);
        if (tableNode == null || !(tableNode.payload instanceof TableInfo tb)) return;
        var server = node.server;
        var db = node.databaseName;
        Ui.bg(owner, () -> {
            var mgr = explorer.managerFor(server, db);
            if (mgr == null) {
                Ui.error(owner, "Not connected", "Couldn't open database '" + db + "'.");
                return;
            }
            work.run(new Ctx(tableNode, new TableRef(tb.schema(), tb.name()), server, db, mgr, new TableInspector(mgr)));
        });
    }

    private boolean isLarge(Ctx c) throws Exception {
        var cfg = AppConfig.current();
        return c.ti().summary(c.s(), c.t()).isLarge(cfg.largeTableRows, cfg.largeTableBytes);
    }

    /** Shows a change dialog (built and shown on the EDT), then applies or opens its SQL. Off the EDT. */
    private void show(Ctx c, Supplier<? extends ChangeDialog> make, String opTitle, Kind folder) throws Exception {
        record Outcome(ChangeDialog.Choice choice, List<DdlStatement> statements) { }
        var out = Ui.call(() -> {
            var d = make.get();
            var choice = d.showDialog();
            return new Outcome(choice, d.statements());
        });
        switch (out.choice()) {
            case APPLY -> apply(c, opTitle, out.statements(), folder);
            case OPEN_IN_TAB -> openQuery.open(script(out.statements()), c.server(), c.db());
            case CANCEL -> { }
        }
    }

    static String script(List<DdlStatement> statements) {
        var tx = statements.stream().filter(DdlStatement::transactional).toList();
        var nonTx = statements.stream().filter(s -> !s.transactional()).toList();
        var sb = new StringBuilder();
        if (!tx.isEmpty()) {
            sb.append("BEGIN;\nSET LOCAL lock_timeout = '").append(DdlApplier.DEFAULT_LOCK_TIMEOUT).append("';\n\n");
            sb.append(tx.stream().map(s -> "-- " + s.lockDescription() + "\n" + s.sql()).collect(Collectors.joining("\n\n")));
            sb.append("\n\nCOMMIT;\n");
        }
        if (!nonTx.isEmpty()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("-- Cannot run inside a transaction:\n");
            sb.append(nonTx.stream().map(s -> "-- " + s.lockDescription() + "\n" + s.sql()).collect(Collectors.joining("\n\n")));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Runs statements in the Output panel and reloads the table, opening {@code folder}. Off the EDT. */
    private void apply(Ctx c, String opTitle, List<DdlStatement> statements, Kind folder) {
        if (statements == null || statements.isEmpty()) return;
        var op = Ui.call(() -> {
            showOutput.run();
            return output.begin(opTitle + " — " + c.db());
        });
        var ok = false;
        DdlApplier.Result result = null;
        try {
            result = new DdlApplier(c.mgr()).apply(statements, false, op.emit(), op.token());
            ok = result.success();
        } catch (Exception ex) {
            op.emit().accept(new OperationEvent(Severity.ERROR, ConnectionErrors.message(ex)));
        } finally {
            output.end(ok);
        }
        var dropped = statements.stream().anyMatch(s -> s.sql().startsWith("DROP TABLE"));
        var renamed = statements.stream().anyMatch(s -> s.sql().startsWith("ALTER TABLE") && s.sql().contains(" RENAME TO "));
        Ui.edt(() -> {
            if (dropped || renamed) {
                // The table node itself changed: reload its Tables folder.
                if (c.tableNode().getParent() instanceof ExplorerNode schemaFolder
                        && schemaFolder.getParent() instanceof ExplorerNode schema) {
                    explorer.refreshNode(schema);
                }
            } else {
                explorer.refreshTable(c.tableNode(), folder);
            }
        });
        if (result != null && !result.success() && "55P03".equals(result.sqlState())) {
            Ui.warn(owner, "Table is busy", result.message());
        }
    }

    private void confirm(ExplorerNode node, String title, String header, String explanation, List<DdlStatement> statements,
                         Kind folder) {
        withCtx(node, c -> show(c, () -> new SqlConfirmDialog(owner, title, header, explanation, statements), title, folder));
    }

    private void rename(ExplorerNode node, String kind, String current, Kind folder,
                        java.util.function.Function<String, DdlStatement> builder) {
        withCtx(node, c -> show(c, () -> new RenameDialog(owner, "Rename " + kind, kind, current, builder),
                "Rename " + kind + " " + current, folder));
    }

    private void drop(ExplorerNode node, String kind, String name, List<Dependency> deps,
                      java.util.function.Function<Boolean, DdlStatement> builder, Kind folder) {
        withCtx(node, c -> show(c, () -> new DropDialog(owner, "Drop " + kind, kind, name, deps, builder),
                "Drop " + kind + " " + name, folder));
    }

    // ---- Columns ---------------------------------------------------------

    private void newColumn(ExplorerNode node) {
        withCtx(node, c -> {
            var types = c.ti().dataTypes();
            show(c, () -> ColumnDialog.create(owner, c.table(), types), "Add column to " + c.table(), Kind.COLUMNS_FOLDER);
        });
    }

    private void editColumn(ExplorerNode node, ColumnDetail col) {
        withCtx(node, c -> {
            var types = c.ti().dataTypes();
            show(c, () -> ColumnDialog.edit(owner, c.table(), col, types), "Alter column " + col.name(), Kind.COLUMNS_FOLDER);
        });
    }

    private void dropColumn(ExplorerNode node, ColumnDetail col) {
        withCtx(node, c -> {
            var deps = new DependencyInspector(c.mgr()).forColumn(c.s(), c.t(), col.name());
            show(c, () -> new DropDialog(owner, "Drop column", "column", col.name(), deps,
                    cascade -> DdlBuilder.dropColumn(c.s(), c.t(), col.name(), cascade)),
                    "Drop column " + col.name(), Kind.COLUMNS_FOLDER);
        });
    }

    // ---- Indexes ---------------------------------------------------------

    private void newIndex(ExplorerNode node) {
        withCtx(node, c -> {
            var cols = c.ti().columns(c.s(), c.t());
            var large = isLarge(c);
            show(c, () -> IndexDialog.create(owner, c.table(), cols, large), "Create index on " + c.table(), Kind.INDEXES_FOLDER);
        });
    }

    private void editIndex(ExplorerNode node, IndexDetail ix) {
        withCtx(node, c -> {
            var cols = c.ti().columns(c.s(), c.t());
            var large = isLarge(c);
            show(c, () -> IndexDialog.edit(owner, c.table(), cols, ix, large), "Rebuild index " + ix.name(), Kind.INDEXES_FOLDER);
        });
    }

    private void reindex(ExplorerNode node, IndexDetail ix) {
        withCtx(node, c -> {
            var large = isLarge(c);
            var concurrently = new JCheckBox("CONCURRENTLY (doesn't block writes; slower, can't run in a transaction)", large);
            var options = new JPanel(new MigLayout("insets 0", "[]"));
            options.add(concurrently);
            show(c, () -> {
                var d = SqlConfirmDialog.withOptions(owner, "Reindex", "Reindex " + ix.name(),
                        "Rebuilds the index from the table. Useful after heavy churn or to repair an INVALID index.",
                        options, () -> List.of(DdlBuilder.reindex(c.s(), c.t(), ix.name(), concurrently.isSelected())));
                concurrently.addActionListener(e -> d.contentChanged());
                return d;
            }, "Reindex " + ix.name(), Kind.INDEXES_FOLDER);
        });
    }

    private void dropIndex(ExplorerNode node, IndexDetail ix) {
        withCtx(node, c -> {
            var deps = new DependencyInspector(c.mgr()).forIndex(c.s(), ix.name());
            var large = isLarge(c);
            // A big table gets CONCURRENTLY unless CASCADE is needed (they can't be combined).
            show(c, () -> new DropDialog(owner, "Drop index", "index", ix.name(), deps,
                    cascade -> DdlBuilder.dropIndex(c.s(), c.t(), ix.name(), large && !cascade, cascade)),
                    "Drop index " + ix.name(), Kind.INDEXES_FOLDER);
        });
    }

    // ---- Constraints -----------------------------------------------------

    private void newForeignKey(ExplorerNode node) {
        withCtx(node, c -> {
            var cols = c.ti().columns(c.s(), c.t());
            var indexes = c.ti().indexes(c.s(), c.t());
            var tables = c.ti().tables();
            var large = isLarge(c);
            show(c, () -> ForeignKeyDialog.create(owner, c.table(), cols, indexes, tables, columnsOf(c), keysOf(c), large),
                    "Add foreign key to " + c.table(), Kind.KEYS_FOLDER);
        });
    }

    private void newKey(ExplorerNode node, boolean primary) {
        withCtx(node, c -> {
            var cols = c.ti().columns(c.s(), c.t());
            show(c, () -> KeyDialog.create(owner, c.table(), cols, primary),
                    (primary ? "Add primary key to " : "Add unique constraint to ") + c.table(),
                    primary ? Kind.KEYS_FOLDER : Kind.CONSTRAINTS_FOLDER);
        });
    }

    private void newCheck(ExplorerNode node) {
        withCtx(node, c -> {
            var large = isLarge(c);
            show(c, () -> CheckDialog.create(owner, c.table(), large), "Add check constraint to " + c.table(),
                    Kind.CONSTRAINTS_FOLDER);
        });
    }

    private void newExclusion(ExplorerNode node) {
        withCtx(node, c -> {
            var cols = c.ti().columns(c.s(), c.t());
            show(c, () -> ExclusionDialog.create(owner, c.table(), cols), "Add exclusion constraint to " + c.table(),
                    Kind.CONSTRAINTS_FOLDER);
        });
    }

    private void editConstraint(ExplorerNode node, ConstraintDetail con) {
        withCtx(node, c -> {
            var cols = c.ti().columns(c.s(), c.t());
            var title = "Change constraint " + con.name();
            switch (con.type()) {
                case FOREIGN_KEY -> {
                    var indexes = c.ti().indexes(c.s(), c.t());
                    var tables = c.ti().tables();
                    var large = isLarge(c);
                    show(c, () -> ForeignKeyDialog.edit(owner, c.table(), cols, indexes, tables, columnsOf(c), keysOf(c),
                            large, con), title, Kind.KEYS_FOLDER);
                }
                case CHECK -> {
                    var large = isLarge(c);
                    show(c, () -> CheckDialog.edit(owner, c.table(), con, large), title, Kind.CONSTRAINTS_FOLDER);
                }
                case PRIMARY_KEY -> show(c, () -> KeyDialog.edit(owner, c.table(), cols, con), title, Kind.KEYS_FOLDER);
                case UNIQUE -> show(c, () -> KeyDialog.edit(owner, c.table(), cols, con), title, Kind.CONSTRAINTS_FOLDER);
                case EXCLUSION -> show(c, () -> ExclusionDialog.edit(owner, c.table(), cols, con), title, Kind.CONSTRAINTS_FOLDER);
            }
        });
    }

    private void dropConstraint(ExplorerNode node, TableRef on, String name, Kind folder) {
        withCtx(node, c -> {
            var deps = new DependencyInspector(c.mgr()).forConstraint(on.schema(), on.name(), name);
            show(c, () -> new DropDialog(owner, "Drop constraint", "constraint", name, deps,
                    cascade -> DdlBuilder.dropConstraint(on.schema(), on.name(), name, cascade)),
                    "Drop constraint " + name, folder);
        });
    }

    /** FK dialog lookups; they run on the dialog's own background thread. */
    private static java.util.function.Function<TableRef, List<String>> columnsOf(Ctx c) {
        return ref -> {
            try {
                return c.ti().columns(ref.schema(), ref.name()).stream().map(ColumnDetail::name).toList();
            } catch (Exception ex) {
                return List.of();
            }
        };
    }

    private static java.util.function.Function<TableRef, List<List<String>>> keysOf(Ctx c) {
        return ref -> {
            try {
                return c.ti().candidateKeys(ref.schema(), ref.name());
            } catch (Exception ex) {
                return List.of();
            }
        };
    }

    // ---- Triggers --------------------------------------------------------

    private void newTrigger(ExplorerNode node) {
        withCtx(node, c -> {
            var cols = c.ti().columns(c.s(), c.t());
            var fns = c.ti().triggerFunctions();
            show(c, () -> withNewFunction(c, TriggerDialog.create(owner, c.table(), cols, fns)),
                    "Create trigger on " + c.table(), Kind.TRIGGERS_FOLDER);
        });
    }

    private void editTrigger(ExplorerNode node, TriggerDetail tr) {
        withCtx(node, c -> {
            var cols = c.ti().columns(c.s(), c.t());
            var fns = c.ti().triggerFunctions();
            show(c, () -> withNewFunction(c, TriggerDialog.edit(owner, c.table(), cols, tr, fns)),
                    "Recreate trigger " + tr.name(), Kind.TRIGGERS_FOLDER);
        });
    }

    /** "New trigger function…" closes the dialog and opens a template in a query tab. */
    private TriggerDialog withNewFunction(Ctx c, TriggerDialog d) {
        d.onNewFunction(() -> {
            d.dispose();
            var fn = DdlBuilder.qualified(c.s(), c.t() + "_trigger_fn");
            var sql = """
                    -- Trigger function for %s. Run this, then right-click the table → New trigger…
                    CREATE OR REPLACE FUNCTION %s()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        -- NEW / OLD hold the row; TG_OP is 'INSERT', 'UPDATE', 'DELETE' or 'TRUNCATE'.
                        NEW.updated_at := now();
                        RETURN NEW;
                    END;
                    $$;
                    """.formatted(c.table(), fn);
            Ui.bg(owner, () -> openQuery.open(sql, c.server(), c.db()));
        });
        return d;
    }

    // ---- Table -----------------------------------------------------------

    private void renameTable(ExplorerNode node) {
        withCtx(node, c -> show(c, () -> new RenameDialog(owner, "Rename table", "table", c.t(),
                n -> DdlBuilder.renameTable(c.s(), c.t(), n)), "Rename table " + c.table(), null));
    }

    private void truncateTable(ExplorerNode node) {
        withCtx(node, c -> {
            var restart = new JCheckBox("RESTART IDENTITY (reset identity / serial counters)");
            var cascade = new JCheckBox("CASCADE (also truncate tables with foreign keys to this one)");
            var options = new JPanel(new MigLayout("insets 0, wrap 1", "[]"));
            options.add(restart);
            options.add(cascade);
            show(c, () -> {
                var d = SqlConfirmDialog.withOptions(owner, "Truncate table", "Truncate " + c.table(),
                        "Deletes every row at once. This can't be undone.", options,
                        () -> List.of(DdlBuilder.truncateTable(c.s(), c.t(), restart.isSelected(), cascade.isSelected())));
                restart.addActionListener(e -> d.contentChanged());
                cascade.addActionListener(e -> d.contentChanged());
                return d;
            }, "Truncate " + c.table(), null);
        });
    }

    private void dropTable(ExplorerNode node) {
        withCtx(node, c -> {
            var deps = new DependencyInspector(c.mgr()).forTable(c.s(), c.t());
            show(c, () -> new DropDialog(owner, "Drop table", "table", c.table().toString(), deps,
                    cascade -> DdlBuilder.dropTable(c.s(), c.t(), cascade)), "Drop table " + c.table(), null);
        });
    }

    private void showProperties(ExplorerNode node) {
        withCtx(node, c -> {
            var props = new TablePropertiesInspector(c.mgr()).properties(c.s(), c.t());
            Ui.edt(() -> new TablePropertiesDialog(owner, c.table().toString(), props).showDialog());
        });
    }

    private void scriptCreate(ExplorerNode node) {
        withCtx(node, c -> openQuery.open(c.ti().scriptCreate(c.s(), c.t()), c.server(), c.db()));
    }

    private void viewDependencies(ExplorerNode node) {
        withCtx(node, c -> {
            var deps = new DependencyInspector(c.mgr()).forTable(c.s(), c.t());
            var sb = new StringBuilder();
            if (deps.isEmpty()) {
                sb.append("Nothing else depends on ").append(c.table()).append('.');
            } else {
                sb.append("These depend on ").append(c.table()).append(":\n\n");
                for (var d : deps) {
                    sb.append("• ").append(d.kind()).append("  ").append(d.name());
                    if (d.detail() != null && !d.detail().isBlank()) sb.append(" — ").append(d.detail());
                    sb.append('\n');
                }
            }
            Ui.info(owner, "Dependencies of " + c.table(), sb.toString());
        });
    }
}

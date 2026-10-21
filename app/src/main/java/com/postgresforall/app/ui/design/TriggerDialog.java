package com.postgresforall.app.ui.design;

import com.formdev.flatlaf.FlatClientProperties;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlSpecs.TriggerSpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.model.TableObjects.TriggerDetail;
import com.postgresforall.core.service.DdlBuilder;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Create or edit a trigger. Editing drops and recreates it (keeping it disabled if it was). */
public final class TriggerDialog extends ChangeDialog {

    private static final String[] TIMINGS = {"BEFORE", "AFTER", "INSTEAD OF"};
    private static final String[] LEVELS = {"ROW", "STATEMENT"};
    private static final String[] EVENTS = {"INSERT", "UPDATE", "DELETE", "TRUNCATE"};

    private final TableRef table;
    private final TriggerDetail existing;
    private final JTextField name;
    private final DesignForms.AutoName autoName;
    private final JComboBox<String> timing;
    private final List<JCheckBox> events = new ArrayList<>();
    private final DesignForms.CheckList updateOf;
    private final JComboBox<String> level;
    private final RSyntaxTextArea when = DesignForms.sqlArea(2, true);
    private final JComboBox<String> function;
    private final JButton newFunction = new JButton("New trigger function…");
    private Runnable onNewFunction;

    public static TriggerDialog create(Component owner, TableRef table, List<ColumnDetail> columns,
                                       List<String> triggerFunctions) {
        return new TriggerDialog(owner, table, columns, null, triggerFunctions);
    }

    public static TriggerDialog edit(Component owner, TableRef table, List<ColumnDetail> columns, TriggerDetail existing,
                                     List<String> triggerFunctions) {
        return new TriggerDialog(owner, table, columns, existing, triggerFunctions);
    }

    private TriggerDialog(Component owner, TableRef table, List<ColumnDetail> columns, TriggerDetail existing,
                          List<String> triggerFunctions) {
        super(owner, existing == null ? "New trigger" : "Edit trigger",
                existing == null ? "New trigger on " + table : "Edit trigger " + existing.name(),
                existing == null ? "Runs a trigger function when rows change."
                        : "Postgres can't change a trigger in place, so this drops it and creates it again.");
        this.table = table;
        this.existing = existing;
        setHeaderIcon(Icons.get("bell", Icons.LARGE));

        Runnable changed = this::onChanged;
        name = DesignForms.field(existing == null ? "" : existing.name(), "trigger name");
        autoName = new DesignForms.AutoName(name, existing == null);
        timing = DesignForms.combo(TIMINGS, existing == null ? "BEFORE" : existing.timing(), changed);
        var eventPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        var existingEvents = existing == null ? List.of("INSERT", "UPDATE") : existing.events();
        for (var ev : EVENTS) {
            var b = DesignForms.check(ev, existingEvents.stream().anyMatch(x -> x.equalsIgnoreCase(ev)), changed);
            events.add(b);
            eventPanel.add(b);
            eventPanel.add(new JLabel("   "));
        }
        updateOf = new DesignForms.CheckList(DesignForms.names(columns),
                existing == null ? List.of() : parseUpdateOf(existing.definition()), changed);
        level = DesignForms.combo(LEVELS, existing == null ? "ROW" : existing.level(), changed);
        if (existing != null && existing.whenExpr() != null) when.setText(existing.whenExpr());
        DesignForms.onChange(when, changed);
        function = DesignForms.editableCombo(triggerFunctions, existing == null ? "" : existing.function(), changed);
        newFunction.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        newFunction.setIcon(Icons.get("square-function", Icons.SMALL));
        newFunction.addActionListener(e -> {
            if (onNewFunction != null) onNewFunction.run();
        });
        newFunction.setVisible(false);

        var form = DesignForms.form();
        form.add(DesignForms.label("Name"));
        form.add(name);
        form.add(DesignForms.label("Fires"));
        form.add(timing, "split 3, growx 0");
        form.add(DesignForms.label("for each"), "gapleft 12");
        form.add(level, "growx 0");
        form.add(DesignForms.label("On"));
        form.add(eventPanel);
        form.add(DesignForms.label("Update of"), "top");
        form.add(updateOf);
        form.add(DesignForms.label("When"), "top, gaptop 4");
        form.add(DesignForms.scrolled(when), "hmin 44");
        form.add(new JLabel());
        form.add(DesignForms.note("Optional, e.g. OLD.status IS DISTINCT FROM NEW.status (row triggers only)."));
        form.add(DesignForms.label("Function"));
        form.add(function);
        form.add(new JLabel());
        form.add(newFunction, "growx 0");
        setForm(form);
        onChanged();
    }

    /** Shows the "New trigger function…" link; the caller opens its own editor and refreshes. */
    public void onNewFunction(Runnable r) {
        onNewFunction = r;
        newFunction.setVisible(r != null);
    }

    private List<String> selectedEvents() {
        return events.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList();
    }

    private void onChanged() {
        var evs = selectedEvents();
        updateOf.setEnabled(evs.contains("UPDATE"));
        var row = "ROW".equals(level.getSelectedItem());
        when.setEnabled(row);
        if (existing == null) {
            var t = ((String) timing.getSelectedItem()).toLowerCase(Locale.ROOT).replace(' ', '_');
            autoName.suggest(table.name(), List.of(t), "trg");
        }
        contentChanged();
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        var n = name.getText().trim();
        var fn = DesignForms.comboText(function);
        var evs = selectedEvents();
        if (n.isEmpty()) throw new IllegalArgumentException("The trigger needs a name.");
        if (evs.isEmpty()) throw new IllegalArgumentException("Choose at least one event.");
        if (fn.isEmpty()) throw new IllegalArgumentException("Choose the trigger function to run.");
        var row = "ROW".equals(level.getSelectedItem());
        if (row && evs.contains("TRUNCATE")) throw new IllegalArgumentException("TRUNCATE triggers must be FOR EACH STATEMENT.");
        var fnName = fn.endsWith("()") ? fn.substring(0, fn.length() - 2) : fn;
        var spec = new TriggerSpec(table.schema(), table.name(), n, (String) timing.getSelectedItem(), evs,
                evs.contains("UPDATE") ? updateOf.values() : List.of(), row,
                row ? DesignForms.blankToNull(when.getText()) : null, fnName);
        var out = new ArrayList<DdlStatement>();
        if (existing != null) out.add(DdlBuilder.dropTrigger(table.schema(), table.name(), existing.name()));
        out.add(DdlBuilder.createTrigger(spec));
        if (existing != null && !existing.enabled()) out.add(DdlBuilder.setTriggerEnabled(table.schema(), table.name(), n, false));
        return out;
    }

    /** "… UPDATE OF a, b ON public.t …" → [a, b]. */
    private static List<String> parseUpdateOf(String definition) {
        if (definition == null) return List.of();
        var up = definition.toUpperCase(Locale.ROOT);
        var i = up.indexOf("UPDATE OF ");
        if (i < 0) return List.of();
        var end = up.indexOf(" ON ", i);
        var orAt = up.indexOf(" OR ", i);
        if (orAt >= 0 && (end < 0 || orAt < end)) end = orAt;
        if (end < 0) return List.of();
        return DesignForms.splitList(definition.substring(i + 10, end));
    }
}

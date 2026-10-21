package com.postgresforall.app.ui.design;

import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlSpecs.CheckSpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.TableObjects.ConstraintDetail;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.service.DdlBuilder;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Create or edit a CHECK constraint. Editing drops and re-adds it. */
public final class CheckDialog extends ChangeDialog {

    private final TableRef table;
    private final ConstraintDetail existing;
    private final JTextField name;
    private final DesignForms.AutoName autoName;
    private final RSyntaxTextArea expression = DesignForms.sqlArea(3, true);
    private final JCheckBox notValid;
    private final JCheckBox noInherit;

    public static CheckDialog create(Component owner, TableRef table, boolean largeTable) {
        return new CheckDialog(owner, table, null, largeTable);
    }

    public static CheckDialog edit(Component owner, TableRef table, ConstraintDetail existing, boolean largeTable) {
        return new CheckDialog(owner, table, existing, largeTable);
    }

    private CheckDialog(Component owner, TableRef table, ConstraintDetail existing, boolean largeTable) {
        super(owner, existing == null ? "New check constraint" : "Edit check constraint",
                existing == null ? "New check constraint on " + table : "Edit check constraint " + existing.name(),
                existing == null ? "Every row must make this expression true (or null)."
                        : "Postgres can't change a check in place, so this drops it and adds it again.");
        this.table = table;
        this.existing = existing;
        setHeaderIcon(Icons.get("list-checks", Icons.LARGE));

        Runnable changed = this::onChanged;
        name = DesignForms.field(existing == null ? "" : existing.name(), "constraint name");
        autoName = new DesignForms.AutoName(name, existing == null);
        if (existing != null) expression.setText(checkExpression(existing.definition()));
        DesignForms.onChange(expression, changed);
        notValid = DesignForms.check("Don't block writes while checking existing rows (NOT VALID, then VALIDATE)", largeTable, changed);
        noInherit = DesignForms.check("Don't apply to child tables (NO INHERIT)",
                existing != null && existing.definition() != null
                        && existing.definition().toUpperCase(Locale.ROOT).contains("NO INHERIT"), changed);

        var form = DesignForms.form();
        form.add(DesignForms.label("Name"));
        form.add(name);
        form.add(DesignForms.label("Expression"), "top, gaptop 4");
        form.add(DesignForms.scrolled(expression), "hmin 60");
        form.add(new JLabel());
        form.add(DesignForms.note("For example: price >= 0  or  ends_at > starts_at"));
        form.add(new JLabel());
        form.add(notValid);
        form.add(new JLabel());
        form.add(noInherit);
        setForm(form);
        onChanged();
    }

    private void onChanged() {
        if (existing == null) autoName.suggest(table.name(), List.of(), "check");
        contentChanged();
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        var n = name.getText().trim();
        var expr = expression.getText().trim();
        if (n.isEmpty()) throw new IllegalArgumentException("The check constraint needs a name.");
        if (expr.isEmpty()) throw new IllegalArgumentException("Enter the expression every row must satisfy.");
        var nv = notValid.isSelected();
        var out = new ArrayList<DdlStatement>();
        if (existing != null) out.add(DdlBuilder.dropConstraint(table.schema(), table.name(), existing.name(), false));
        out.add(DdlBuilder.addCheck(new CheckSpec(table.schema(), table.name(), n, expr, nv, noInherit.isSelected())));
        if (nv) out.add(DdlBuilder.validateConstraint(table.schema(), table.name(), n));
        return out;
    }

    /** "CHECK ((price >= 0)) NOT VALID" → "price >= 0". */
    static String checkExpression(String definition) {
        var inner = DesignForms.parenAfter(definition, "CHECK");
        if (inner == null) return definition == null ? "" : definition;
        var e = inner.trim();
        while (e.startsWith("(") && e.endsWith(")") && wrapsWhole(e)) e = e.substring(1, e.length() - 1).trim();
        return e;
    }

    private static boolean wrapsWhole(String e) {
        var depth = 0;
        for (var i = 0; i < e.length(); i++) {
            if (e.charAt(i) == '(') depth++;
            else if (e.charAt(i) == ')') depth--;
            if (depth == 0 && i < e.length() - 1) return false;
        }
        return true;
    }
}

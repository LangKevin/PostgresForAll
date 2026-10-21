package com.postgresforall.app.ui.design;

import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlStatement;

import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.util.List;
import java.util.function.Function;

/** Rename any table object; {@code builder} turns the new name into the statement. */
public final class RenameDialog extends ChangeDialog {

    private final String currentName;
    private final Function<String, DdlStatement> builder;
    private final JTextField name;

    public RenameDialog(Component owner, String title, String objectKind, String currentName,
                        Function<String, DdlStatement> builder) {
        super(owner, title, "Rename " + objectKind + " " + currentName,
                "Code that refers to the old name (queries, functions, application code) won't follow the rename.");
        this.currentName = currentName;
        this.builder = builder;
        setHeaderIcon(Icons.get("pencil", Icons.LARGE));
        name = DesignForms.field(currentName, "new name");
        DesignForms.onChange(name, this::contentChanged);
        var form = DesignForms.form();
        form.add(DesignForms.label("New name"));
        form.add(name);
        form.add(new JLabel());
        form.add(DesignForms.note("Names are case sensitive; anything other than lower-case letters, digits and _ gets quoted."));
        setForm(form);
        name.selectAll();
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        var n = name.getText().trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Enter the new name.");
        if (n.equals(currentName)) throw new IllegalArgumentException("Enter a different name.");
        if (n.length() > 63) throw new IllegalArgumentException("Postgres names are at most 63 characters.");
        return List.of(builder.apply(n));
    }
}

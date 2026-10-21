package com.postgresforall.app.ui.design;

import com.postgresforall.app.ui.dialogs.BaseDialog;
import com.postgresforall.core.model.DdlStatement;
import net.miginfocom.swing.MigLayout;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.util.List;
import java.util.function.Supplier;

/**
 * Confirms a fixed or option-driven change (validate, reindex, enable/disable a
 * trigger, truncate …) with the same preview and lock list as the other dialogs.
 */
public final class SqlConfirmDialog extends ChangeDialog {

    private final Supplier<List<DdlStatement>> statements;

    public SqlConfirmDialog(Component owner, String title, String header, String explanation, List<DdlStatement> statements) {
        this(owner, title, header, explanation, null, () -> statements);
    }

    private SqlConfirmDialog(Component owner, String title, String header, String explanation, JComponent options,
                             Supplier<List<DdlStatement>> statements) {
        super(owner, title, header, null);
        this.statements = statements;
        var form = new JPanel(new MigLayout("insets 0, fillx, wrap 1, gapy 8", "[grow,fill]"));
        if (explanation != null && !explanation.isBlank()) form.add(BaseDialog.wrappingText(explanation, 60));
        if (options != null) form.add(options);
        setForm(form);
    }

    /** {@code options} should call {@link #contentChanged()} when they change. */
    public static SqlConfirmDialog withOptions(Component owner, String title, String header, String explanation,
                                               JComponent options, Supplier<List<DdlStatement>> statements) {
        return new SqlConfirmDialog(owner, title, header, explanation, options, statements);
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        return statements.get();
    }

    @Override
    public void contentChanged() {
        super.contentChanged();
    }
}

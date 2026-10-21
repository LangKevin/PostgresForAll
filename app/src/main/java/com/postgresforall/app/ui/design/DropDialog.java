package com.postgresforall.app.ui.design;

import com.formdev.flatlaf.FlatClientProperties;
import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.TableObjects.Dependency;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.Component;
import java.util.List;
import java.util.function.Function;

/**
 * Drops something after showing what depends on it. "Drop" stays disabled until
 * the object's name is typed exactly; CASCADE is only offered when there are
 * dependents to cascade to.
 */
public final class DropDialog extends ChangeDialog {

    private final String objectName;
    private final Function<Boolean, DdlStatement> builderByCascade;
    private final JCheckBox cascade;
    private final JTextField confirm;

    public DropDialog(Component owner, String title, String objectKind, String objectName, List<Dependency> dependencies,
                      Function<Boolean, DdlStatement> builderByCascade) {
        super(owner, title, "Drop " + objectKind + " " + objectName, "This can't be undone.");
        this.objectName = objectName;
        this.builderByCascade = builderByCascade;
        setHeaderIcon(Icons.tinted("trash-2", Icons.LARGE, Palette::danger));
        var deps = dependencies == null ? List.<Dependency>of() : dependencies;

        var form = new JPanel(new MigLayout("insets 0, fillx, wrap 1, gapy 6", "[grow,fill]"));
        if (!deps.isEmpty()) {
            var banner = new JPanel(new MigLayout("insets 8 10 8 10, fillx", "[grow,fill]"));
            banner.setBackground(Palette.bannerWarning());
            banner.setBorder(BorderFactory.createLineBorder(Palette.bannerWarningBorder()));
            var text = new JLabel("<html>These depend on it and will block the drop unless you choose CASCADE, "
                    + "which drops them too.</html>", Icons.tinted("triangle-alert", Icons.SMALL, Palette::warning), JLabel.LEFT);
            banner.add(text);
            form.add(banner);
            var model = new DefaultListModel<String>();
            for (var d : deps) {
                model.addElement(d.kind() + "  " + d.name() + (d.detail() == null || d.detail().isBlank() ? "" : "  — " + d.detail()));
            }
            var list = new JList<>(model);
            list.setVisibleRowCount(Math.min(6, Math.max(2, deps.size())));
            form.add(new JScrollPane(list));
        } else {
            form.add(DesignForms.note("Nothing else depends on it."));
        }
        cascade = DesignForms.check("Drop dependent objects too (CASCADE)", false, this::contentChanged);
        cascade.setEnabled(!deps.isEmpty());
        form.add(cascade);
        var prompt = DesignForms.label("Type " + objectName + " to confirm");
        form.add(prompt, "gaptop 6");
        confirm = DesignForms.field("", objectName);
        DesignForms.onChange(confirm, this::contentChanged);
        form.add(confirm);
        setForm(form);

        var drop = applyButton();
        drop.setText("Drop");
        drop.putClientProperty(FlatClientProperties.STYLE_CLASS, "danger");
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        return List.of(builderByCascade.apply(cascade.isSelected()));
    }

    @Override
    protected boolean canApply() {
        return confirm != null && confirm.getText().equals(objectName);
    }
}

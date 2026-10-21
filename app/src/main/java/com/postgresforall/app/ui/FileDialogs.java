package com.postgresforall.app.ui;

import com.postgresforall.core.platform.Platform;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Open / save pickers: the native macOS sheet (AWT FileDialog) on a Mac,
 * Swing's chooser elsewhere. Safe to call from any thread; null on cancel.
 */
public final class FileDialogs {
    private FileDialogs() { }

    /** @param extensions e.g. "sql"; the first one names the filter */
    public static Path open(Component owner, String title, Path initialDir, String filterName, String... extensions) {
        return Ui.call(() -> Platform.isMac()
                ? awt(owner, title, initialDir, null, FileDialog.LOAD, extensions)
                : swing(owner, title, initialDir, null, false, filterName, extensions));
    }

    public static Path save(Component owner, String title, Path initialDir, String suggestedName,
                            String filterName, String... extensions) {
        return Ui.call(() -> {
            var p = Platform.isMac()
                    ? awt(owner, title, initialDir, suggestedName, FileDialog.SAVE, extensions)
                    : swing(owner, title, initialDir, suggestedName, true, filterName, extensions);
            if (p == null || extensions.length == 0) return p;
            var name = p.getFileName().toString();
            return name.contains(".") ? p : p.resolveSibling(name + "." + extensions[0]);
        });
    }

    private static Path swing(Component owner, String title, Path initialDir, String suggestedName, boolean save,
                              String filterName, String... extensions) {
        var chooser = new JFileChooser(initialDir == null ? null : initialDir.toFile());
        chooser.setDialogTitle(title);
        if (extensions.length > 0) {
            var filter = new FileNameExtensionFilter(filterName, extensions);
            chooser.addChoosableFileFilter(filter);
            chooser.setFileFilter(filter);
        }
        if (suggestedName != null) {
            chooser.setSelectedFile(new File(initialDir == null ? new File(".") : initialDir.toFile(), suggestedName));
        }
        var result = save ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) return null;
        var file = chooser.getSelectedFile().toPath();
        if (save && file.toFile().exists() && !Ui.confirm(owner, title,
                file.getFileName() + " already exists. Replace it?", true)) {
            return null;
        }
        return file;
    }

    private static Path awt(Component owner, String title, Path initialDir, String suggestedName, int mode,
                            String... extensions) {
        var window = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        if (owner instanceof java.awt.Window w) window = w;
        FileDialog fd = window instanceof Frame f ? new FileDialog(f, title, mode)
                : window instanceof Dialog d ? new FileDialog(d, title, mode)
                : new FileDialog((Frame) null, title, mode);
        if (initialDir != null) fd.setDirectory(initialDir.toString());
        if (suggestedName != null) fd.setFile(suggestedName);
        if (extensions.length > 0 && mode == FileDialog.LOAD) {
            fd.setFilenameFilter((dir, name) -> {
                var lower = name.toLowerCase(Locale.ROOT);
                for (var e : extensions) if (lower.endsWith("." + e)) return true;
                return false;
            });
        }
        fd.setVisible(true);
        if (fd.getFile() == null) return null;
        return Path.of(fd.getDirectory(), fd.getFile());
    }
}

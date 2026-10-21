package com.postgresforall.app.ui.diag;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.theme.Fonts;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.Diagnostics.ContainerState;
import com.postgresforall.core.model.Diagnostics.ProbeStatus;
import com.postgresforall.core.model.Diagnostics.ProbeStep;
import com.postgresforall.core.model.Diagnostics.Snapshot;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.LogIssue;
import com.postgresforall.core.model.LogIssue.FixAction;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.service.ConnectionProbe;
import com.postgresforall.core.service.DockerHost;
import com.postgresforall.core.service.DockerProbe;
import com.postgresforall.core.service.LogAnalyzer;
import com.postgresforall.core.service.ServerHost;
import com.postgresforall.core.service.ServerHost.Kind;
import com.postgresforall.core.service.ServerHost.RunState;
import com.postgresforall.core.service.ServerHosts;
import com.postgresforall.core.service.ServerLogReader;
import com.postgresforall.core.service.UnmanagedHost;
import com.postgresforall.core.util.CancelToken;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * "Postgres Health": step-by-step connection probe with a fix button per
 * failed step, host controls (container / service start-stop) and server logs
 * with recognised issues. Port of the original DiagnosticsViewModel + panel.
 */
public final class DiagnosticsPanel extends JPanel {
    private final Supplier<ConnectionProfile> profileProvider;
    private final DiscoveredServer server;
    private Runnable editCredentials;

    // State (EDT-owned)
    private ServerHost host = new UnmanagedHost("not probed yet");
    private CancelToken running;
    private boolean busy;
    private boolean logsExpanded;
    private final List<ProbeStep> lastSteps = new ArrayList<>();
    private RunState hostState = RunState.UNKNOWN;

    // Widgets
    private final JCheckBox autoRefreshBox = new JCheckBox("Auto-refresh 10s");
    private final JButton refreshBtn = new JButton("Refresh");
    private final RoundedPanel banner = new RoundedPanel(new BorderLayout(10, 0));
    private final JLabel bannerIcon = new JLabel();
    private final JTextArea bannerText = text("");
    private final JPanel stepsPanel = new JPanel();
    private final JLabel detailLine = new JLabel();
    private final JLabel hostActionsTitle = new JLabel("Server Actions");
    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn = new JButton("Stop");
    private final JButton restartBtn = new JButton("Restart");
    private final JButton logsBtn = new JButton("View logs");
    private final JPanel logsArea = new JPanel();
    private final RoundedPanel bootBanner = new RoundedPanel(new BorderLayout(10, 0));
    private final JLabel bootBannerText = new JLabel();
    private final JPanel issuesBox = new JPanel();
    private final JTextArea rawLogs = new JTextArea();
    private final JLabel statusLine = new JLabel("Click Refresh to probe.");
    private final JLabel lastChecked = new JLabel("Last checked: never");
    private final Timer autoTimer;

    public DiagnosticsPanel(Supplier<ConnectionProfile> profileProvider, DiscoveredServer serverOrNull) {
        super(new BorderLayout(0, 10));
        this.profileProvider = profileProvider;
        this.server = serverOrNull;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        autoTimer = new Timer(10_000, e -> { if (!busy) refresh(); });
        autoRefreshBox.addActionListener(e -> {
            if (autoRefreshBox.isSelected()) autoTimer.start();
            else autoTimer.stop();
        });

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
        updateButtons();
    }

    // ---- Public API ------------------------------------------------------

    public void onEditCredentials(Runnable r) {
        editCredentials = r;
    }

    public void dispose() {
        autoTimer.stop();
        if (running != null) running.cancel();
    }

    /** Starts an async probe (no-op while busy). Call from the EDT. */
    public void refresh() {
        if (busy) return;
        setBusy(true);
        if (running != null) running.cancel();
        var ct = new CancelToken();
        running = ct;
        Ui.bgQuiet(() -> {
            try {
                var profile = Ui.call(profileProvider::get);
                var h = server != null ? ServerHosts.forServer(server, null) : ServerHosts.forProfile(profile, ct);
                Ui.edt(() -> {
                    host = h;
                    applyHostTitle();
                    clearSteps();
                    setBanner(null, BannerKind.INFO);
                });
                var snap = new ConnectionProbe(h).run(profile, true, ct);
                if (ct.isCancelled()) return;
                Ui.edt(() -> render(snap));
            } catch (Exception ex) {
                Ui.edt(() -> statusLine.setText("Probe failed: " + ex.getMessage()));
            } finally {
                Ui.edt(() -> setBusy(false));
            }
        });
    }

    // ---- Layout ----------------------------------------------------------

    private JComponent buildTop() {
        var top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        var header = new JPanel(new BorderLayout());
        var title = new JLabel("Postgres Health", Icons.get("activity", Icons.LARGE), JLabel.LEADING);
        title.setIconTextGap(8);
        title.putClientProperty("FlatLaf.styleClass", "h2");
        header.add(title, BorderLayout.WEST);
        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(autoRefreshBox);
        refreshBtn.setIcon(Icons.get("refresh-cw"));
        refreshBtn.addActionListener(e -> refresh());
        right.add(refreshBtn);
        var help = new JButton();
        help.setToolTipText("Show troubleshooting help for all diagnostics.");
        help.putClientProperty("JButton.buttonType", "help");
        help.addActionListener(e -> HelpDialog.show(this, HelpTopic.GENERAL));
        right.add(help);
        header.add(right, BorderLayout.EAST);
        header.setAlignmentX(LEFT_ALIGNMENT);
        top.add(header);
        top.add(Box.createVerticalStrut(12));

        banner.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));
        bannerIcon.setVerticalAlignment(JLabel.TOP);
        banner.add(bannerIcon, BorderLayout.WEST);
        banner.add(bannerText, BorderLayout.CENTER);
        var bannerHelp = new JButton();
        bannerHelp.putClientProperty("JButton.buttonType", "help");
        bannerHelp.setToolTipText("What does this mean and how do I fix it?");
        bannerHelp.addActionListener(e -> HelpDialog.show(this, inferHelpTopic()));
        var bh = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bh.setOpaque(false);
        bh.add(bannerHelp);
        banner.add(bh, BorderLayout.EAST);
        banner.setVisible(false);
        banner.setAlignmentX(LEFT_ALIGNMENT);
        top.add(banner);
        return top;
    }

    private JComponent buildCenter() {
        // Steps, detail line and (when shown) the logs all scroll together, so
        // expanding the logs can never push the header or footer off screen.
        var holder = new WidthTrackingPanel(new BorderLayout());
        var steps = new JPanel(new BorderLayout());
        stepsPanel.setLayout(new BoxLayout(stepsPanel, BoxLayout.Y_AXIS));
        steps.add(stepsPanel, BorderLayout.NORTH);
        detailLine.putClientProperty("FlatLaf.styleClass", "subtle");
        detailLine.setFont(detailLine.getFont().deriveFont(Font.ITALIC));
        detailLine.setBorder(BorderFactory.createEmptyBorder(6, 12, 0, 0));
        steps.add(detailLine, BorderLayout.CENTER);
        var inner = new JPanel(new BorderLayout());
        inner.add(steps, BorderLayout.NORTH);
        inner.add(buildLogsArea(), BorderLayout.CENTER);
        holder.add(inner, BorderLayout.NORTH);
        var scroll = new JScrollPane(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JComponent buildLogsArea() {
        logsArea.setLayout(new BoxLayout(logsArea, BoxLayout.Y_AXIS));
        logsArea.setAlignmentX(LEFT_ALIGNMENT);
        logsArea.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        bootBanner.fill(Palette::bannerSuccess).outline(Palette::bannerSuccessBorder);
        bootBanner.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        bootBanner.add(new JLabel(Icons.tinted("circle-check", Icons.NORMAL, Palette::success)), BorderLayout.WEST);
        bootBanner.add(bootBannerText, BorderLayout.CENTER);
        bootBanner.setAlignmentX(LEFT_ALIGNMENT);
        bootBanner.setVisible(false);
        logsArea.add(bootBanner);
        logsArea.add(Box.createVerticalStrut(6));

        issuesBox.setLayout(new BoxLayout(issuesBox, BoxLayout.Y_AXIS));
        issuesBox.setAlignmentX(LEFT_ALIGNMENT);
        issuesBox.setVisible(false);
        logsArea.add(issuesBox);

        rawLogs.setEditable(false);
        rawLogs.setFont(Fonts.mono(-2));
        rawLogs.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        var logScroll = new JScrollPane(rawLogs);
        logScroll.setPreferredSize(new Dimension(400, 180));
        logScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        logScroll.setAlignmentX(LEFT_ALIGNMENT);
        logsArea.add(logScroll);
        logsArea.setVisible(false);
        return logsArea;
    }

    /** A panel that follows the viewport's width, so wrapped text wraps instead of scrolling sideways. */
    private static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
        WidthTrackingPanel(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private JComponent buildBottom() {
        var bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        var actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(10, 0, 0, 0)));
        hostActionsTitle.putClientProperty("FlatLaf.styleClass", "sectionTitle");
        hostActionsTitle.setAlignmentX(LEFT_ALIGNMENT);
        actions.add(hostActionsTitle);
        actions.add(Box.createVerticalStrut(6));
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        startBtn.setIcon(Icons.tinted("play", Icons.NORMAL, Palette::success));
        stopBtn.setIcon(Icons.tinted("square", Icons.NORMAL, Palette::danger));
        restartBtn.setIcon(Icons.get("refresh-cw"));
        logsBtn.setIcon(Icons.get("file-text"));
        for (var b : List.of(startBtn, stopBtn, restartBtn, logsBtn)) {
            b.setIconTextGap(6);
            row.add(b);
        }
        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> stop());
        restartBtn.addActionListener(e -> restart());
        logsBtn.addActionListener(e -> toggleLogs());
        actions.add(row);


        actions.setAlignmentX(LEFT_ALIGNMENT);
        bottom.add(actions);

        var footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        statusLine.putClientProperty("FlatLaf.styleClass", "sectionTitle");
        lastChecked.putClientProperty("FlatLaf.styleClass", "small muted");
        footer.add(statusLine);
        footer.add(Box.createVerticalStrut(2));
        footer.add(lastChecked);
        footer.setAlignmentX(LEFT_ALIGNMENT);
        bottom.add(footer);
        return bottom;
    }

    private static JTextArea text(String s) {
        var t = new JTextArea(s);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setFont(UIManager.getFont("Label.font"));
        t.setBorder(null);
        return t;
    }

    // ---- Rendering -------------------------------------------------------

    private void setBusy(boolean b) {
        busy = b;
        refreshBtn.setEnabled(!b);
        updateButtons();
    }

    private void applyHostTitle() {
        hostActionsTitle.setText(switch (host.kind()) {
            case DOCKER -> "Container Actions — " + host.name();
            case SERVICE -> "Service Actions — " + host.name() + (host.needsElevation() ? " (asks for admin)" : "");
            default -> "Server Actions";
        });
        var control = host.canControl();
        startBtn.setVisible(control);
        stopBtn.setVisible(control);
        restartBtn.setVisible(control);
    }

    private void clearSteps() {
        lastSteps.clear();
        stepsPanel.removeAll();
        stepsPanel.revalidate();
        stepsPanel.repaint();
    }

    private void render(Snapshot snap) {
        clearSteps();
        for (var s : snap.steps()) {
            lastSteps.add(s);
            var card = stepCard(s, fixFor(s, snap));
            card.setAlignmentX(LEFT_ALIGNMENT);
            stepsPanel.add(card);
            stepsPanel.add(Box.createVerticalStrut(6));
        }
        stepsPanel.revalidate();
        stepsPanel.repaint();

        lastChecked.setText("Last checked: " + snap.checkedAt().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        hostState = snap.hostStatus() == null ? RunState.UNKNOWN : snap.hostStatus().state();
        updateButtons();
        updateBanner(snap);
        var detail = snap.hostStatus() == null ? null : snap.hostStatus().detail();
        detailLine.setText(detail == null ? "" : detail);
        detailLine.setVisible(detail != null && !detail.isBlank());
        statusLine.setText(snap.allOk() ? "All green." : "Some checks failed — see steps above.");
        statusLine.setIcon(snap.allOk()
                ? Icons.tinted("circle-check", Icons.NORMAL, Palette::success)
                : Icons.tinted("triangle-alert", Icons.NORMAL, Palette::warning));
    }

    /** One probe step as a card: status icon, name, message, timing, fix button. */
    private JComponent stepCard(ProbeStep s, Fix fix) {
        var failed = s.status() == ProbeStatus.FAILED;
        var card = new RoundedPanel(new GridBagLayout())
                .fill(failed ? Palette::bannerError : RoundedPanel::panelBackground)
                .outline(failed ? Palette::danger : Palette::border);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        var c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 10);
        c.anchor = GridBagConstraints.WEST;

        var icon = new JLabel(statusIcon(s.status()));
        icon.setToolTipText(s.status().name().toLowerCase(Locale.ROOT));
        var name = new JLabel(s.name());
        name.putClientProperty("FlatLaf.styleClass", "semibold");
        name.setPreferredSize(new Dimension(230, name.getPreferredSize().height));
        var msg = new JLabel(s.message() == null ? "" : s.message());
        msg.putClientProperty("FlatLaf.styleClass", failed ? "danger" : "muted");
        msg.setToolTipText(s.message());
        var elapsed = new JLabel(s.elapsedMs() > 0 ? String.format("%,.0f ms", s.elapsedMs()) : "");
        elapsed.putClientProperty("FlatLaf.styleClass", "small subtle");

        c.gridx = 0;
        card.add(icon, c);
        c.gridx = 1;
        card.add(name, c);
        c.gridx = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        card.add(msg, c);
        c.gridx = 3;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        card.add(elapsed, c);
        if (fix != null) {
            var b = new JButton(fix.label(), Icons.get("wrench", Icons.SMALL));
            b.putClientProperty("JComponent.sizeVariant", "small");
            b.setToolTipText("Apply the most likely fix for this issue.");
            b.addActionListener(e -> fix.action().run());
            c.gridx = 4;
            c.insets = new Insets(0, 0, 0, 0);
            card.add(b, c);
        }
        return card;
    }

    private static javax.swing.Icon statusIcon(ProbeStatus s) {
        return switch (s) {
            case OK -> Icons.tinted("circle-check", Icons.NORMAL, Palette::success);
            case FAILED -> Icons.tinted("circle-x", Icons.NORMAL, Palette::danger);
            case SKIPPED -> Icons.tinted("circle-minus", Icons.NORMAL, Palette::subtle);
            default -> Icons.tinted("loader", Icons.NORMAL, Palette::subtle);
        };
    }



    private record Fix(String label, Runnable action) { }

    private Fix fixFor(ProbeStep step, Snapshot snap) {
        if (step.status() != ProbeStatus.FAILED) return null;
        var name = step.name();
        var startLabel = host.kind() == Kind.SERVICE ? "Start service" : "Start container";

        // TCP failed — the right fix depends on what hosts the server.
        if (name.startsWith("TCP ")) {
            if (host instanceof DockerHost) {
                if (!snap.dockerAvailable()) return new Fix("Start Docker Desktop", this::startDockerDesktopAndRefresh);
                if (snap.container() != null && snap.container().state() == ContainerState.NOT_FOUND) {
                    return new Fix("Open guide", this::openGuide);
                }
            }
            if (host.canControl() && snap.hostStatus() != null && snap.hostStatus().state() == RunState.STOPPED) {
                return new Fix(startLabel, this::start);
            }
            return new Fix("Retry", this::refresh);
        }
        if (name.equals("Docker daemon")) return new Fix("Start Docker Desktop", this::startDockerDesktopAndRefresh);
        if (name.startsWith("Container")) {
            if (snap.container() != null && snap.container().state() == ContainerState.NOT_FOUND) {
                return new Fix("Open guide", this::openGuide);
            }
            return new Fix("Start container", this::start);
        }
        if (name.startsWith("Service ") && snap.hostStatus() != null
                && snap.hostStatus().state() == RunState.STOPPED) {
            return new Fix("Start service", this::start);
        }
        if (name.startsWith("Auth ")) {
            return new Fix("Edit credentials", () -> { if (editCredentials != null) editCredentials.run(); });
        }
        if (name.equals("SELECT 1")) return new Fix("Retry", this::refresh);
        return null;
    }

    private void updateButtons() {
        var control = host.canControl();
        startBtn.setEnabled(!busy && control && hostState == RunState.STOPPED);
        stopBtn.setEnabled(!busy && control && hostState == RunState.RUNNING);
        restartBtn.setEnabled(!busy && control && (hostState == RunState.RUNNING || hostState == RunState.STOPPED));
        // Logs come over SQL for any host, so they're always worth trying.
        logsBtn.setEnabled(true);
    }

    private enum BannerKind { INFO, WARNING, ERROR }

    private void setBanner(String text, BannerKind kind) {
        if (text == null) {
            banner.setVisible(false);
        } else {
            bannerText.setText(text);
            bannerText.setForeground(UIManager.getColor("Label.foreground"));
            switch (kind) {
                case INFO -> {
                    banner.fill(Palette::bannerInfo).outline(Palette::bannerInfoBorder);
                    bannerIcon.setIcon(Icons.tinted("info", Icons.NORMAL, Palette::notice));
                }
                case WARNING -> {
                    banner.fill(Palette::bannerWarning).outline(Palette::bannerWarningBorder);
                    bannerIcon.setIcon(Icons.tinted("triangle-alert", Icons.NORMAL, Palette::warning));
                }
                case ERROR -> {
                    banner.fill(Palette::bannerError).outline(Palette::bannerErrorBorder);
                    bannerIcon.setIcon(Icons.tinted("circle-x", Icons.NORMAL, Palette::danger));
                }
            }
            banner.setVisible(true);
        }
        revalidate();
        repaint();
    }

    private void updateBanner(Snapshot snap) {
        var tcpOk = snap.steps().stream().anyMatch(s -> s.name().startsWith("TCP ") && s.status() == ProbeStatus.OK);
        if (host.kind() == Kind.UNMANAGED) {
            if (!tcpOk) {
                setBanner("Nothing answers on this port, and it's a " + host.description() + " — start the "
                        + "server yourself, then click Refresh.", BannerKind.INFO);
            }
            return;
        }
        if (tcpOk) return; // host details are informational while the server answers

        if (host instanceof DockerHost && !snap.dockerAvailable()) {
            setBanner("This server is a Docker container and Docker isn't running. Start Docker, then click Refresh.",
                    BannerKind.ERROR);
            return;
        }
        var state = snap.hostStatus() == null ? null : snap.hostStatus().state();
        if (state == RunState.MISSING) {
            setBanner(host instanceof DockerHost
                    ? "Container '" + host.name() + "' doesn't exist. Create it (e.g. 'docker compose up -d'), "
                    + "or point Host/Port at a native PostgreSQL install."
                    : host.description() + " isn't installed any more.", BannerKind.WARNING);
            return;
        }
        if (state == RunState.STOPPED) {
            setBanner(host.description() + " is stopped. Use Start below"
                    + (host.needsElevation()
                    ? (Platform.isWindows() ? " (Windows will ask for admin)." : " (macOS will ask for an administrator password).")
                    : "."), BannerKind.WARNING);
        }
    }

    HelpTopic inferHelpTopic() {
        // Earliest failure first.
        for (var s : lastSteps) {
            if (s.status() != ProbeStatus.FAILED) continue;
            var n = s.name();
            if (n.equals("Docker daemon")) return HelpTopic.DOCKER_NOT_RUNNING;
            if (n.startsWith("Service ")) return HelpTopic.SERVICE_STOPPED;
            if (n.startsWith("Container")) {
                return s.message() != null && s.message().toLowerCase(Locale.ROOT).contains("not found")
                        ? HelpTopic.CONTAINER_NOT_FOUND : HelpTopic.CONTAINER_STOPPED;
            }
            if (n.startsWith("TCP ")) return HelpTopic.TCP_CLOSED;
            if (n.startsWith("Auth ")) return HelpTopic.AUTH_FAILED;
            if (n.equals("SELECT 1")) return HelpTopic.QUERY_FAILED;
        }
        return HelpTopic.GENERAL;
    }

    // ---- Host actions ----------------------------------------------------

    private interface HostCall {
        ProcessResult run(ServerHost h, CancelToken ct);
    }

    private void start() {
        runHostAction("Starting", "Start failed: ", ServerHost::start, true, "Server running and port open.",
                "Started but port never opened (30s).");
    }

    private void restart() {
        runHostAction("Restarting", "Restart failed: ", ServerHost::restart, true, "Restarted.",
                "Restarted but port never opened (30s).");
    }

    private void stop() {
        if (busy) return;
        if (!Ui.confirm(this, "Confirm Stop", "Stop " + host.description() + "? Active connections will be dropped.",
                true)) {
            return;
        }
        runHostAction("Stopping", "Stop failed: ", ServerHost::stop, false, "Stopped.", null);
    }

    private void runHostAction(String verb, String failPrefix, HostCall call, boolean waitForPort, String okMsg,
                               String timeoutMsg) {
        if (busy) return;
        setBusy(true);
        var h = host;
        statusLine.setText(verb + " " + h.description() + "...");
        Ui.bgQuiet(() -> {
            try {
                var r = call.run(h, CancelToken.NONE);
                if (r.exitCode() != 0) {
                    var err = r.stdErr().trim();
                    Ui.edt(() -> statusLine.setText(failPrefix + (err.isEmpty() ? "exit code " + r.exitCode() : err)));
                    return;
                }
                if (waitForPort) {
                    var profile = Ui.call(profileProvider::get);
                    Ui.edt(() -> statusLine.setText("Waiting for port " + profile.port + "..."));
                    var ok = ServerHosts.waitForPort(profile.host, profile.port, Duration.ofSeconds(30), CancelToken.NONE);
                    Ui.edt(() -> statusLine.setText(ok ? okMsg : timeoutMsg));
                } else {
                    Ui.edt(() -> statusLine.setText(okMsg));
                }
            } finally {
                Ui.edt(() -> {
                    setBusy(false);
                    refresh();
                });
            }
        });
    }

    private void startDockerDesktopAndRefresh() {
        if (busy) return;
        setBusy(true);
        statusLine.setText("Starting Docker Desktop...");
        var docker = host instanceof DockerHost d ? d.probe() : null;
        Ui.bgQuiet(() -> {
            try {
                var launched = tryLaunchDockerDesktop();
                if (launched != null) {
                    Ui.edt(() -> statusLine.setText(launched + "  (click ? for help)"));
                    HelpDialog.show(this, HelpTopic.DOCKER_NOT_INSTALLED);
                    return;
                }
                Ui.edt(() -> statusLine.setText("Waiting for Docker daemon (up to 90s)..."));
                var deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                while (System.nanoTime() < deadline) {
                    if (docker != null && docker.isDaemonReachable(CancelToken.NONE)) {
                        Ui.edt(() -> statusLine.setText("Docker daemon is up. Re-probing..."));
                        break;
                    }
                    Thread.sleep(2000);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                Ui.edt(() -> {
                    setBusy(false);
                    refresh();
                });
            }
        });
    }

    /** Launches Docker Desktop; null on success, else a message saying why not. */
    private static String tryLaunchDockerDesktop() {
        try {
            if (Platform.isWindows()) {
                var local = System.getenv("LOCALAPPDATA");
                var candidates = new ArrayList<Path>();
                candidates.add(Path.of("C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe"));
                if (local != null) candidates.add(Path.of(local, "Programs", "Docker", "Docker", "Docker Desktop.exe"));
                for (var p : candidates) {
                    if (Files.isRegularFile(p)) {
                        new ProcessBuilder(p.toString()).start();
                        return null;
                    }
                }
            } else if (Platform.isMac()) {
                var apps = List.of(Path.of("/Applications/Docker.app"),
                        Path.of(System.getProperty("user.home"), "Applications", "Docker.app"));
                for (var p : apps) {
                    if (Files.isDirectory(p)) {
                        new ProcessBuilder("open", "-a", p.toString()).start();
                        return null;
                    }
                }
            }
        } catch (IOException ex) {
            return "Failed to start Docker Desktop: " + ex.getMessage();
        }
        return "Docker Desktop not found in standard locations. Install it from docker.com or start it manually.";
    }

    private void openGuide() {
        var path = LogFixActions.guidePath();
        if (Files.isRegularFile(path) && Ui.open(path)) {
            statusLine.setText("Opened the ScheduleMe connection guide.");
            return;
        }
        Ui.info(this, "Create the " + host.name() + " container",
                "To create the container, from your ScheduleMe repo run:\n\n  docker compose up -d\n\n"
                        + "(Looked for guide at " + path + " but didn't find it.)");
    }

    // ---- Logs ------------------------------------------------------------

    private void toggleLogs() {
        logsExpanded = !logsExpanded;
        logsArea.setVisible(logsExpanded);
        logsBtn.setText(logsExpanded ? "Hide logs" : "View logs");
        revalidate();
        repaint();
        if (logsExpanded) reloadLogs();
    }

    private void reloadLogs() {
        rawLogs.setText("Loading logs...");
        issuesBox.removeAll();
        issuesBox.setVisible(false);
        bootBanner.setVisible(false);
        var h = host;
        Ui.bgQuiet(() -> {
            try {
                // Server log file over SQL works for any host; fall back to the
                // host's own output (docker logs) when the server keeps no file.
                var profile = Ui.call(profileProvider::get);
                var tail = ServerLogReader.readTail(profile, 50, 64 * 1024);
                var raw = tail.text() != null ? tail.text() : h.logs(50, CancelToken.NONE);
                if (raw == null) {
                    Ui.edt(() -> rawLogs.setText("No server logs available: " + tail.reason() + "."));
                    return;
                }
                var shown = tail.text() != null ? "[" + tail.file() + "]\n" + raw : explainLogsResult(raw, h);
                List<LogIssue> issues = List.of();
                var lower = raw.toLowerCase(Locale.ROOT);
                // Only analyse content that is actually Postgres output.
                if (!lower.contains("no such container") && !lower.contains("cannot connect to the docker daemon")) {
                    issues = LogAnalyzer.analyze(raw);
                }
                var finalIssues = issues;
                var docker = h instanceof DockerHost d ? d.probe() : null;
                Ui.edt(() -> renderLogs(shown, finalIssues, docker, profile.port));
            } catch (Exception ex) {
                Ui.edt(() -> rawLogs.setText("Failed to fetch logs: " + ex.getMessage()));
            }
        });
    }

    private void renderLogs(String shown, List<LogIssue> issues, DockerProbe docker, int port) {
        rawLogs.setText(shown);
        rawLogs.setCaretPosition(0);
        issuesBox.removeAll();

        // If the only issue is a healthy boot, lift it out as a banner.
        if (issues.size() == 1 && issues.get(0).fixAction() == FixAction.HEALTHY_BOOT) {
            var lines = issues.get(0).matchedLines();
            var ts = extractTimestamp(lines.isEmpty() ? "" : lines.get(lines.size() - 1));
            bootBannerText.setText(ts != null ? "Postgres last booted cleanly at " + ts : "Postgres last booted cleanly");
            bootBanner.setVisible(true);
        } else if (!issues.isEmpty()) {
            var fixes = new LogFixActions(this, docker, port, this::reloadLogs);
            var head = new JLabel("Issues found (" + issues.size() + ")");
            head.putClientProperty("FlatLaf.styleClass", "sectionTitle");
            head.setAlignmentX(LEFT_ALIGNMENT);
            issuesBox.add(head);
            issuesBox.add(Box.createVerticalStrut(4));
            for (var issue : issues) {
                var card = issueCard(issue, fixes.forAction(issue.fixAction(), issue.matchedLines()));
                card.setAlignmentX(LEFT_ALIGNMENT);
                issuesBox.add(card);
                issuesBox.add(Box.createVerticalStrut(4));
            }
            issuesBox.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
            issuesBox.setVisible(true);
        }
        logsArea.revalidate();
        revalidate();
        repaint();
    }

    private JComponent issueCard(LogIssue issue, LogFixActions.Fix fix) {
        Supplier<Color> bar;
        String sev;
        String icon;
        switch (issue.severity()) {
            case CRITICAL -> { bar = Palette::critical; sev = "CRITICAL"; icon = "circle-x"; }
            case ERROR -> { bar = Palette::danger; sev = "ERROR"; icon = "circle-x"; }
            case WARNING -> { bar = Palette::caution; sev = "WARN"; icon = "triangle-alert"; }
            case UNKNOWN -> { bar = Palette::warning; sev = "UNKNOWN"; icon = "circle-help"; }
            case INFO -> { bar = Palette::success; sev = "INFO"; icon = "info"; }
            default -> { bar = Palette::subtle; sev = ""; icon = "info"; }
        }
        var card = new RoundedPanel(new BorderLayout(10, 0))
                .fill(RoundedPanel::panelBackground).outline(Palette::border).accent(bar);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        var text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        var titleText = issue.matchCount() > 1 ? issue.title() + "  × " + issue.matchCount() : issue.title();
        var headRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        headRow.setOpaque(false);
        headRow.setAlignmentX(LEFT_ALIGNMENT);
        var sevLabel = new JLabel(sev, Icons.tinted(icon, Icons.SMALL, bar), JLabel.LEADING);
        sevLabel.setForeground(bar.get());
        sevLabel.putClientProperty("FlatLaf.styleClass", "small");
        sevLabel.setFont(sevLabel.getFont().deriveFont(Font.BOLD));
        var title = new JLabel(titleText);
        title.putClientProperty("FlatLaf.styleClass", "semibold");
        headRow.add(sevLabel);
        headRow.add(title);
        text.add(headRow);
        text.add(Box.createVerticalStrut(2));
        var desc = text(issue.description());
        desc.putClientProperty("FlatLaf.styleClass", "muted");
        desc.setForeground(Palette.muted());
        desc.setColumns(50);
        desc.setAlignmentX(LEFT_ALIGNMENT);
        text.add(desc);
        var sampleText = issue.matchedLines().isEmpty() ? "" : issue.matchedLines().get(issue.matchedLines().size() - 1);
        var sample = new JLabel(sampleText);
        sample.setFont(Fonts.mono(-2));
        sample.putClientProperty("FlatLaf.styleClass", "subtle");
        sample.setToolTipText("<html><pre>" + Ui.escapeHtml(String.join("\n", issue.matchedLines())) + "</pre></html>");
        sample.setAlignmentX(LEFT_ALIGNMENT);
        text.add(Box.createVerticalStrut(4));
        text.add(sample);
        card.add(text, BorderLayout.CENTER);

        if (fix != null) {
            var b = new JButton(fix.label());
            b.putClientProperty("JComponent.sizeVariant", "small");
            b.addActionListener(e -> fix.action().run());
            var holder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            holder.setOpaque(false);
            holder.add(b);
            card.add(holder, BorderLayout.EAST);
        }
        return card;
    }

    private static final Pattern TIMESTAMP = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}[T\\s]\\d{2}:\\d{2}:\\d{2})");

    private static String extractTimestamp(String line) {
        var m = TIMESTAMP.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static String explainLogsResult(String raw, ServerHost h) {
        var t = raw == null ? "" : raw.trim();
        var lower = t.toLowerCase(Locale.ROOT);

        // Common: container was deleted between probe and click.
        if (lower.contains("no such container")) {
            return "The container '" + h.name() + "' doesn't exist on this Docker "
                    + "daemon, so there are no logs to show.\n\n"
                    + "How to fix:\n"
                    + "  1. Open a terminal in your ScheduleMe project root.\n"
                    + "  2. Run:  docker compose up -d\n"
                    + "  3. Wait ~10s for Postgres to initialise.\n"
                    + "  4. Click Refresh on this Diagnostics tab.\n\n"
                    + "Already created it under a different name? Run 'docker ps -a' in a "
                    + "terminal to confirm — we expect a container literally named '" + h.name() + "'.\n\n"
                    + "Original docker output:\n  " + t;
        }
        if (lower.contains("cannot connect to the docker daemon") || lower.contains("error during connect")) {
            return "Docker daemon isn't reachable, so there are no logs to fetch.\n\n"
                    + "How to fix:\n"
                    + (Platform.isMac()
                    ? "  1. Start Docker Desktop (Applications → Docker).\n"
                    + "  2. Wait for the whale menu-bar icon to stop animating.\n"
                    : "  1. Start Docker Desktop (Start menu → 'Docker Desktop').\n"
                    + "  2. Wait for the whale tray icon to stop animating.\n")
                    + "  3. Click Refresh on this Diagnostics tab.\n\n"
                    + "Original docker output:\n  " + t;
        }
        if (t.isBlank()) return "(No log output — Postgres may not have written anything yet.)";
        return t;
    }

    @Override
    public void removeNotify() {
        autoTimer.stop();
        super.removeNotify();
    }

    /** The window this panel sits in, for dialogs. */
    Component ownerWindow() {
        var w = SwingUtilities.getWindowAncestor(this);
        return w != null ? w : this;
    }
}

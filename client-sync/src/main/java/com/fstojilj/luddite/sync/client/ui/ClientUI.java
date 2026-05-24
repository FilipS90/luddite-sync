package com.fstojilj.luddite.sync.client.ui;

import com.fstojilj.luddite.sync.client.service.ClientSyncService;
import com.fstojilj.luddite.sync.client.service.RootDirService;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.fstojilj.luddite.sync.client.service.ClientSyncService.serverDirs;

/**
 * Minimalistic retro-terminal Swing UI for the Luddite Sync client.
 *
 * <p>Activate by setting {@code sync.client.ui=swing} in {@code application.yml}.
 * When active, all System.out output is also routed to the in-window log panel.
 */
@Component
@ConditionalOnProperty(name = "sync.client.ui", havingValue = "swing")
@RequiredArgsConstructor
@Slf4j
public class ClientUI {

    // ── Retro palette ─────────────────────────────────────────────────────────
    private static final Color BG = new Color(0x0D, 0x0D, 0x0D);
    private static final Color BG_PANEL = new Color(0x13, 0x13, 0x13);
    private static final Color BG_CELL = new Color(0x1A, 0x1A, 0x1A);
    private static final Color FG = new Color(0x00, 0xE5, 0x40);   // matrix green
    private static final Color FG_DIM = new Color(0x00, 0x80, 0x25);
    private static final Color FG_AMBER = new Color(0xFF, 0xB0, 0x00);   // amber accent
    private static final Color FG_RED = new Color(0xFF, 0x44, 0x44);
    private static final Color BORDER_CLR = new Color(0x00, 0x66, 0x1A);
    private static final Font MONO_BOLD = new Font("Courier New", Font.BOLD, 13);
    private static final Font MONO = new Font("Courier New", Font.PLAIN, 12);
    private static final Font MONO_SM = new Font("Courier New", Font.PLAIN, 11);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Spring deps ───────────────────────────────────────────────────────────
    private final RootDirService rootDirService;
    private final ClientSyncService clientSyncService;
    private final ApplicationContext applicationContext;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDir;

    // ── Swing components (all touched only on EDT) ────────────────────────────
    private JFrame frame;
    private JLabel statusDot;
    private JLabel statusLabel;
    private DefaultListModel<String> serverListModel;
    private DefaultTableModel subscribedTableModel;
    private JTable subscribedTable;
    private JList<String> serverList;
    private JTextArea logArea;
    private Timer refreshTimer;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() {
        // Route System.out to the log panel as well
        redirectSystemOut();
        SwingUtilities.invokeLater(this::buildAndShow);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildAndShow() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        frame = new JFrame();
        frame.setTitle("LUDDITE SYNC — CLIENT");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setBackground(BG);
        frame.getContentPane().setBackground(BG);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApp();
            }
        });

        frame.setLayout(new BorderLayout(0, 0));
        frame.add(buildTitleBar(), BorderLayout.NORTH);
        frame.add(buildCenter(), BorderLayout.CENTER);
        frame.add(buildStatusBar(), BorderLayout.SOUTH);

        frame.setMinimumSize(new Dimension(820, 560));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Refresh every 2 s
        refreshTimer = new Timer(2000, e -> refreshData());
        refreshTimer.setInitialDelay(500);
        refreshTimer.start();

        appendLog("UI started. Mirror: " + mirrorDir);
    }

    // ── Title bar ─────────────────────────────────────────────────────────────

    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG);
        bar.setBorder(new EmptyBorder(8, 12, 4, 12));

        // ASCII-art style title
        JLabel title = label("  ██╗     ██╗   ██╗██████╗ ██████╗ ██╗████████╗███████╗   by FilipS90", MONO_BOLD, FG);
        bar.add(title, BorderLayout.WEST);

        // Status indicator
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        statusPanel.setBackground(BG);
        statusDot = label("●", MONO_BOLD, FG_DIM);
        statusLabel = label("CONNECTING...", MONO_SM, FG_DIM);
        statusPanel.add(statusDot);
        statusPanel.add(statusLabel);
        bar.add(statusPanel, BorderLayout.EAST);

        // Separator
        JSeparator sep = new JSeparator();
        sep.setForeground(BORDER_CLR);
        sep.setBackground(BG);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.add(bar, BorderLayout.CENTER);
        wrapper.add(sep, BorderLayout.SOUTH);
        return wrapper;
    }

    // ── Center pane ───────────────────────────────────────────────────────────

    private JComponent buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildDirectoriesPanel(), buildLogPanel());
        split.setDividerLocation(280);
        split.setDividerSize(4);
        split.setBackground(BG);
        split.setBorder(null);
        split.setOpaque(true);
        return split;
    }

    // ── Directories panel ─────────────────────────────────────────────────────

    private JPanel buildDirectoriesPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(6, 12, 4, 12));

        // Left — server dirs
        serverListModel = new DefaultListModel<>();
        serverList = new JList<>(serverListModel);
        serverList.setBackground(BG_CELL);
        serverList.setForeground(FG_AMBER);
        serverList.setFont(MONO);
        serverList.setSelectionBackground(BORDER_CLR);
        serverList.setSelectionForeground(Color.WHITE);
        serverList.setBorder(new EmptyBorder(4, 6, 4, 6));
        serverList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) subscribeSelected();
            }
        });
        JScrollPane serverScroll = retroScroll(serverList);
        JPanel serverPanel = titledPanel("SERVER DIRECTORIES  (double-click to subscribe)", serverScroll);

        // Middle — buttons
        JPanel btnPanel = buildActionButtons();

        // Right — subscribed dirs table
        subscribedTableModel = new DefaultTableModel(new Object[]{"DIRECTORY", "SYNC VERSION"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        subscribedTable = new JTable(subscribedTableModel);
        styleTable(subscribedTable);
        JScrollPane subScroll = retroScroll(subscribedTable);
        JPanel subPanel = titledPanel("SUBSCRIBED  (double-click to stop sync)", subScroll);

        // Double-click to unsubscribe
        subscribedTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) stopSync(false);
            }
        });

        JSplitPane dirSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, serverPanel, subPanel);
        dirSplit.setDividerLocation(300);
        dirSplit.setDividerSize(4);
        dirSplit.setBackground(BG);
        dirSplit.setBorder(null);

        panel.add(dirSplit, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    // ── Action buttons ────────────────────────────────────────────────────────

    private JPanel buildActionButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        panel.setBackground(BG);
        panel.add(retroButton("[ START SYNC >> ]", FG, this::subscribeSelected));
        panel.add(retroButton("[ SYNC PRIVATE ]", FG_AMBER, this::syncPrivate));
        panel.add(retroButton("[ STOP SYNC ]", FG_AMBER, () -> stopSync(false)));
        panel.add(retroButton("[ STOP & DELETE ]", FG_RED, () -> stopSync(true)));
        panel.add(retroButton("[ REFRESH ]", FG_AMBER, this::doRefresh));
        panel.add(retroButton("[ EXIT ]", FG_RED, this::exitApp));

        return panel;
    }

    // ── Log panel ─────────────────────────────────────────────────────────────

    private JPanel buildLogPanel() {
        logArea = new JTextArea();
        logArea.setBackground(BG_PANEL);
        logArea.setForeground(FG_DIM);
        logArea.setFont(MONO_SM);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(false);
        logArea.setCaretColor(FG);
        logArea.setBorder(new EmptyBorder(4, 6, 4, 6));

        JScrollPane scroll = retroScroll(logArea);
        return titledPanel("LOG", scroll);
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG);
        bar.setBorder(new EmptyBorder(4, 12, 6, 12));

        JLabel mirrorLabel = label("MIRROR ▸ " + mirrorDir.replace('/', java.io.File.separatorChar), MONO_SM, FG_DIM);
        bar.add(mirrorLabel, BorderLayout.WEST);

        JLabel hint = label("DBL-CLICK to subscribe / unsubscribe", MONO_SM, new Color(0x33, 0x33, 0x33));
        bar.add(hint, BorderLayout.EAST);

        JSeparator sep = new JSeparator();
        sep.setForeground(BORDER_CLR);
        sep.setBackground(BG);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.add(sep, BorderLayout.NORTH);
        wrapper.add(bar, BorderLayout.CENTER);
        return wrapper;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void subscribeSelected() {
        String selected = serverList.getSelectedValue();
        if (selected == null) {
            appendLog("[WARN] No server directory selected.");
            return;
        }
        String dir = selected.trim();
        List<String> current = rootDirService.retrieveAllInSyncDirs();
        if (current.contains(dir)) {
            appendLog("[INFO] Already subscribed to: " + dir);
            return;
        }
        rootDirService.registerIfAbsent(dir);
        appendLog("[OK]   Subscribed to: " + dir);
        refreshData();
    }

    private void stopSync(boolean deleteLocalFiles) {
        int row = subscribedTable.getSelectedRow();
        if (row < 0) {
            appendLog("[WARN] No subscribed directory selected.");
            return;
        }
        String dir = (String) subscribedTableModel.getValueAt(row, 0);
        String msg = deleteLocalFiles
                ? "Stop sync AND delete local files for <b>" + dir + "</b>?"
                : "Stop sync for <b>" + dir + "</b>?<br>Local mirror files will be kept.";
        int confirm = JOptionPane.showConfirmDialog(frame,
                retroHtmlMsg(msg),
                deleteLocalFiles ? "CONFIRM STOP & DELETE" : "CONFIRM STOP SYNC",
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        rootDirService.removeDirectory(dir, deleteLocalFiles);
        appendLog(deleteLocalFiles
                ? "[OK]   Stopped sync and deleted local files for: " + dir
                : "[OK]   Stopped sync (local files kept) for: " + dir);
        clientSyncService.reconnect();
        refreshData();
    }

    private void doRefresh() {
        appendLog("[INFO] Reconnecting to server...");
        clientSyncService.reconnect();
        refreshData();
    }

    private void syncPrivate() {
        // Build a small panel with two fields
        JTextField dirField = new JTextField(20);
        JPasswordField passField = new JPasswordField(20);

        dirField.setBackground(new Color(0x1A, 0x1A, 0x1A));
        dirField.setForeground(FG);
        dirField.setCaretColor(FG);
        dirField.setFont(MONO);
        dirField.setBorder(new LineBorder(BORDER_CLR, 1));

        passField.setBackground(new Color(0x1A, 0x1A, 0x1A));
        passField.setForeground(FG);
        passField.setCaretColor(FG);
        passField.setFont(MONO);
        passField.setBorder(new LineBorder(BORDER_CLR, 1));
        passField.setEchoChar('*');

        JPanel inputPanel = new JPanel(new java.awt.GridLayout(4, 1, 0, 4));
        inputPanel.setBackground(BG);
        inputPanel.add(label("DIRECTORY NAME:", MONO_SM, FG_DIM));
        inputPanel.add(dirField);
        inputPanel.add(label("PASSWORD:", MONO_SM, FG_DIM));
        inputPanel.add(passField);

        int result = JOptionPane.showConfirmDialog(frame, inputPanel,
                "SYNC PRIVATE DIRECTORY",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        String dirName = dirField.getText().trim();
        String password = new String(passField.getPassword());

        if (dirName.isEmpty() || password.isEmpty()) {
            appendLog("[WARN] Directory name and password must not be empty.");
            return;
        }

        appendLog("[INFO] Requesting access to private dir: " + dirName);
        clientSyncService.requestPrivateDir(dirName, password);

        // Poll the auth result after a short delay to let the reconnect complete
        new javax.swing.SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                // Wait long enough for the reconnect + auth round-trip (max 8 s)
                for (int i = 0; i < 16; i++) {
                    Thread.sleep(500);
                    java.util.Map<String, Boolean> results = clientSyncService.getPrivateAuthResults();
                    if (results.containsKey(dirName)) {
                        return results.get(dirName);
                    }
                }
                return null; // timeout — no result yet
            }

            @Override
            protected void done() {
                try {
                    Boolean granted = get();
                    if (Boolean.TRUE.equals(granted)) {
                        appendLog("[OK]   Access granted to private dir: " + dirName);
                        refreshData();
                    } else if (Boolean.FALSE.equals(granted)) {
                        appendLog("[WARN] Access denied to private dir: " + dirName);
                        JOptionPane.showMessageDialog(frame,
                                "<html><body style='font-family:Courier New;font-size:12px;"
                                        + "color:#FF4444;background:#0D0D0D;padding:8px'>"
                                        + "ACCESS DENIED<br>Wrong directory name or password.</body></html>",
                                "ACCESS DENIED",
                                JOptionPane.ERROR_MESSAGE);
                        // Ask user to retry with a new password
                        syncPrivate();
                    } else {
                        appendLog("[WARN] Could not confirm auth result for: " + dirName + " — will retry on next reconnect.");
                    }
                } catch (Exception ex) {
                    log.warn("syncPrivate result check error", ex);
                }
            }
        }.execute();
    }

    private void exitApp() {
        int confirm = JOptionPane.showConfirmDialog(frame,
                retroHtmlMsg("Exit Luddite Sync client?"),
                "CONFIRM EXIT", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        refreshTimer.stop();
        frame.dispose();
        SpringApplication.exit(applicationContext, () -> 0);
    }

    // ── Data refresh (DB work off EDT via SwingWorker) ─────────────────────────

    private void refreshData() {
        new SwingWorker<RefreshSnapshot, Void>() {
            @Override
            protected RefreshSnapshot doInBackground() {
                List<String> srv = serverDirs;
                List<SyncHandshakeEntry> subs = rootDirService.findAll(); // DB call — off EDT
                boolean connected = clientSyncService.isConnected();
                return new RefreshSnapshot(srv, subs, connected);
            }

            @Override
            protected void done() {
                try {
                    RefreshSnapshot snap = get();

                    serverListModel.clear();
                    snap.serverDirs().forEach(serverListModel::addElement);

                    subscribedTableModel.setRowCount(0);
                    for (SyncHandshakeEntry e : snap.subscribedDirs()) {
                        subscribedTableModel.addRow(new Object[]{e.dirName(), e.lastSyncVersion()});
                    }

                    Color dotColor = snap.connected() ? FG : FG_RED;
                    statusDot.setForeground(dotColor);
                    statusLabel.setForeground(dotColor);
                    statusLabel.setText(snap.connected() ? "CONNECTED" : "DISCONNECTED");
                } catch (Exception ex) {
                    log.warn("refreshData error", ex);
                }
            }
        }.execute();
    }

    private record RefreshSnapshot(List<String> serverDirs,
                                   List<SyncHandshakeEntry> subscribedDirs,
                                   boolean connected) {
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void appendLog(String msg) {
        String line = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg + "\n";
        SwingUtilities.invokeLater(() -> {
            if (logArea == null) return;   // UI not yet built
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * Redirects {@code System.out} to the log panel.
     * Bytes are accumulated in a {@link ByteArrayOutputStream} and decoded as UTF-8
     * at each newline, so multi-byte characters (Cyrillic, etc.) are never corrupted.
     */
    private void redirectSystemOut() {
        OutputStream interceptor = new OutputStream() {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

            @Override
            public void write(int b) {
                if (b == '\n') {
                    flush();
                } else {
                    buf.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                for (int i = off; i < off + len; i++) write(b[i] & 0xFF);
            }

            @Override
            public void flush() {
                if (buf.size() > 0) {
                    String line = buf.toString(StandardCharsets.UTF_8);
                    buf.reset();
                    if (!line.isBlank()) appendLog(line);
                }
            }
        };
        System.setOut(new PrintStream(interceptor, true, StandardCharsets.UTF_8));
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    private static JLabel label(String text, Font font, Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(fg);
        l.setOpaque(false);
        return l;
    }

    private static JButton retroButton(String text, Color fg, Runnable action) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(getModel().isPressed() ? fg.darker() : BG_CELL);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(MONO_BOLD);
        btn.setForeground(fg);
        btn.setBackground(BG_CELL);
        btn.setFocusPainted(false);
        btn.setBorderPainted(true);
        btn.setBorder(new LineBorder(fg.darker(), 1));
        btn.setContentAreaFilled(false);
        btn.setOpaque(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setBorder(new LineBorder(fg, 1));
                btn.setForeground(fg.brighter());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                btn.setBorder(new LineBorder(fg.darker(), 1));
                btn.setForeground(fg);
            }
        });
        btn.addActionListener(e -> action.run());
        return btn;
    }

    private static JScrollPane retroScroll(JComponent view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBackground(BG_CELL);
        sp.setBorder(new LineBorder(BORDER_CLR, 1));
        sp.getViewport().setBackground(BG_CELL);
        sp.getVerticalScrollBar().setBackground(BG);
        sp.getHorizontalScrollBar().setBackground(BG);
        return sp;
    }

    private static JPanel titledPanel(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);
        TitledBorder border = BorderFactory.createTitledBorder(
                new LineBorder(BORDER_CLR, 1), " " + title + " ");
        border.setTitleFont(MONO_SM);
        border.setTitleColor(FG_DIM);
        panel.setBorder(BorderFactory.createCompoundBorder(border, new EmptyBorder(4, 4, 4, 4)));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static void styleTable(JTable table) {
        table.setBackground(BG_CELL);
        table.setForeground(FG);
        table.setFont(MONO);
        table.setGridColor(BORDER_CLR);
        table.setRowHeight(22);
        table.setSelectionBackground(BORDER_CLR);
        table.setSelectionForeground(Color.WHITE);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setBackground(BG);
        table.getTableHeader().setForeground(FG_DIM);
        table.getTableHeader().setFont(MONO_SM);
        table.getTableHeader().setBorder(new LineBorder(BORDER_CLR, 1));
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(JLabel.RIGHT);
        rightRenderer.setBackground(BG_CELL);
        rightRenderer.setForeground(FG_AMBER);
        rightRenderer.setFont(MONO);
        table.getColumnModel().getColumn(1).setCellRenderer(rightRenderer);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
    }

    private static String retroHtmlMsg(String body) {
        return "<html><body style='font-family:Courier New;font-size:12px;color:#00E540;"
                + "background:#0D0D0D;padding:8px'>" + body + "</body></html>";
    }
}









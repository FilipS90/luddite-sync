package com.fstojilj.luddite.sync.client.ui;

import com.fstojilj.luddite.sync.client.service.ClientSyncService;
import com.fstojilj.luddite.sync.client.service.ConnectionState;
import com.fstojilj.luddite.sync.client.service.DownloadService;
import com.fstojilj.luddite.sync.client.service.HostSettingsService;
import com.fstojilj.luddite.sync.client.service.RootDirService;
import com.fstojilj.luddite.sync.client.service.ServerApiClient;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.text.DefaultCaret;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    // Tolerance (in px) for treating the log viewport as "at the bottom" for auto-scroll purposes.
    private static final int LOG_AUTOSCROLL_SLACK_PX = 4;
    // Thickness (in px) of the draggable edge/corner resize *hit-test* zone around the
    // undecorated main window — kept wide enough to reliably grab with the mouse, independent
    // of how thick the visible border line drawn within it is (see VISIBLE_BORDER_PX).
    private static final int RESIZE_MARGIN = 6;
    // Thickness (in px) of the actual painted border line within the resize margin — kept thin
    // (roughly 1/8-1/10 of RESIZE_MARGIN) so the frame reads as a thin retro outline rather than
    // a thick colored band; the rest of the margin is invisible (painted in BG).
    private static final int VISIBLE_BORDER_PX = 1;

    // ── Spring deps ───────────────────────────────────────────────────────────
    private final RootDirService rootDirService;
    private final ClientSyncService clientSyncService;
    private final ServerApiClient serverApiClient;
    private final DownloadService downloadService;
    private final HostSettingsService hostSettingsService;
    private final ApplicationContext applicationContext;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDir;

    // ── Tree item model ───────────────────────────────────────────────────────

    /**
     * Represents one row in the left tree panel.
     */
    private record TreeItem(String rootDirName, String fullRelPath, int depth, boolean isRootDir, boolean isFile) {
        String displayName() {
            if (isRootDir) return rootDirName;
            int slash = fullRelPath.lastIndexOf('/');
            return slash < 0 ? fullRelPath : fullRelPath.substring(slash + 1);
        }

        String key() {
            return isRootDir ? rootDirName : rootDirName + ":" + fullRelPath;
        }
    }

    // ── Swing components (all touched only on EDT) ────────────────────────────
    private JFrame frame;
    private JLabel statusDot;
    private JLabel statusLabel;
    private DefaultListModel<TreeItem> treeListModel;
    private JList<TreeItem> treeList;
    private DefaultListModel<String> subscribedListModel;
    private JList<String> subscribedList;
    private JButton btnStartSync;
    private JButton btnDownload;
    private JTextArea logArea;
    private JScrollBar logScrollBar;
    private Timer refreshTimer;
    private final Set<String> expandedKeys = new HashSet<>();
    private long lastSubscribedClickMs = 0;
    private JTextField hostField;
    private boolean hostFieldPopulated = false;
    // Guards against the 2 s timer stacking up refresh workers when the server is slow
    // or unreachable. EDT-only: set before execute(), cleared in done().
    private boolean refreshInFlight = false;

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
        // Native OS chrome (white title bar, borders) clashes with the retro-terminal theme —
        // replace it entirely with our own draggable title bar (see buildTitleBar()) and a
        // custom resize border (see wrapWithResizeBorder()) since undecorated frames lose both
        // the OS title-bar drag and the OS edge/corner resize behavior.
        frame.setUndecorated(true);
        frame.setResizable(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setBackground(BG);
        frame.getContentPane().setBackground(BG);
        frame.getRootPane().setBackground(BG);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApp();
            }
        });

        JPanel mainContent = new JPanel(new BorderLayout(0, 0));
        mainContent.setBackground(BG);
        mainContent.add(buildTitleBar(), BorderLayout.NORTH);
        mainContent.add(buildCenter(), BorderLayout.CENTER);

        JPanel southStack = new JPanel();
        southStack.setLayout(new BoxLayout(southStack, BoxLayout.Y_AXIS));
        southStack.setBackground(BG);
        southStack.add(buildHostBar());
        southStack.add(buildStatusBar());
        mainContent.add(southStack, BorderLayout.SOUTH);

        Dimension minSize = new Dimension(490, 460);
        frame.setContentPane(wrapWithResizeBorder(frame, mainContent, minSize));
        frame.setMinimumSize(minSize);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Refresh every 2 s
        refreshTimer = new Timer(2000, e -> {
            populateHostFieldOnce();
            refreshData();
        });
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

        // Status indicator + window controls (this is a fully custom title bar — the frame
        // is undecorated, so minimize/close and dragging must be handled ourselves here).
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        statusPanel.setBackground(BG);
        statusDot = label("●", MONO_BOLD, FG_DIM);
        statusLabel = label("CONNECTING...", MONO_SM, FG_DIM);
        statusPanel.add(statusDot);
        statusPanel.add(statusLabel);
        statusPanel.add(Box.createHorizontalStrut(10));
        statusPanel.add(windowControlButton("_", FG_DIM, () -> frame.setExtendedState(Frame.ICONIFIED)));
        statusPanel.add(windowControlButton("X", FG_RED, this::exitApp));
        bar.add(statusPanel, BorderLayout.EAST);

        // Separator
        JSeparator sep = new JSeparator();
        sep.setForeground(BORDER_CLR);
        sep.setBackground(BG);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.add(bar, BorderLayout.CENTER);
        wrapper.add(sep, BorderLayout.SOUTH);

        // Dragging the undecorated frame: wire the listener to the bar background and the
        // title label directly (Swing dispatches mouse events to the topmost component under
        // the cursor, so the label needs its own listener too, not just its parent panel's).
        enableWindowDrag(bar, frame);
        enableWindowDrag(title, frame);
        return wrapper;
    }

    // ── Center pane ───────────────────────────────────────────────────────────

    private JComponent buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildDirectoriesPanel(), buildLogPanel());
        split.setDividerLocation(210);
        split.setDividerSize(4);
        split.setBackground(BG);
        split.setOpaque(true);
        retroSplitPaneUi(split);

        return split;
    }

    /**
     * Applies the retro-terminal look to a {@link JSplitPane}: a borderless, flat divider
     * painted in the background color instead of the Metal look-and-feel's default bevel.
     *
     * <p>The border must be cleared <em>after</em> {@code setUI} because
     * {@code BasicSplitPaneUI.installUI} re-installs the default (white bevel) border whenever
     * the current border is {@code null} — clearing it beforehand has no lasting effect.
     */
    private static void retroSplitPaneUi(JSplitPane splitPane) {
        splitPane.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                BasicSplitPaneDivider divider = new BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics g) {
                        g.setColor(BG);
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
                divider.setBackground(BG);
                divider.setBorder(BorderFactory.createEmptyBorder());
                return divider;
            }
        });
        splitPane.setBorder(BorderFactory.createEmptyBorder());
    }

    // ── Directories panel ─────────────────────────────────────────────────────

    private JPanel buildDirectoriesPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(6, 12, 4, 12));

        // Left — expandable server dir tree
        treeListModel = new DefaultListModel<>();
        treeList = new JList<>(treeListModel);
        treeList.setBackground(BG_CELL);
        treeList.setFont(MONO);
        treeList.setSelectionBackground(BORDER_CLR);
        treeList.setSelectionForeground(Color.WHITE);
        treeList.setBorder(new EmptyBorder(4, 6, 4, 6));
        treeList.setCellRenderer(buildTreeCellRenderer());
        treeList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1) handleTreeSingleClick(e);
                else if (e.getClickCount() == 2) handleTreeDoubleClick(e);
            }
        });
        JScrollPane treeScroll = retroScroll(treeList);
        JPanel treePanel = titledPanel("SERVER DIRECTORIES  (click to expand, dbl-click to sync)", treeScroll);

        // Middle — buttons
        JPanel btnPanel = buildActionButtons();

        // Right — subscribed dirs list (single column, no version column)
        subscribedListModel = new DefaultListModel<>();
        subscribedList = new JList<>(subscribedListModel);
        subscribedList.setBackground(BG_CELL);
        subscribedList.setForeground(FG);
        subscribedList.setFont(MONO);
        subscribedList.setSelectionBackground(BORDER_CLR);
        subscribedList.setSelectionForeground(Color.WHITE);
        subscribedList.setBorder(new EmptyBorder(4, 6, 4, 6));
        subscribedList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                lastSubscribedClickMs = System.currentTimeMillis();
                if (e.getClickCount() == 2) stopSync(false);
            }
        });
        JScrollPane subScroll = retroScroll(subscribedList);
        JPanel subPanel = titledPanel("SUBSCRIBED  (double-click to stop sync)", subScroll);

        JSplitPane dirSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, subPanel);
        dirSplit.setDividerSize(4);
        dirSplit.setBackground(BG);
        retroSplitPaneUi(dirSplit);
        dirSplit.setResizeWeight(0.75);
        dirSplit.setDividerLocation(0.75);

        panel.add(dirSplit, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    private ListCellRenderer<TreeItem> buildTreeCellRenderer() {
        return (list, value, index, isSelected, cellHasFocus) -> {
            JLabel lbl = new JLabel();
            lbl.setOpaque(true);
            lbl.setFont(MONO);
            lbl.setBorder(new EmptyBorder(1, 4, 1, 4));
            if (value == null) return lbl;
            String indent = "  ".repeat(value.depth() * 2);
            lbl.setText(indent + value.displayName());
            Color fg = value.isFile() ? new Color(0x00, 0xBB, 0x55) : FG_AMBER;
            lbl.setForeground(fg);
            lbl.setBackground(isSelected ? BORDER_CLR : BG_CELL);
            return lbl;
        };
    }

    // ── Action buttons ────────────────────────────────────────────────────────

    private JPanel buildActionButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        panel.setBackground(BG);
        btnStartSync = retroButton("[ START SYNC ]", FG_AMBER, this::subscribeSelected);
        btnDownload = retroButton("[ DOWNLOAD ]", FG_AMBER, this::downloadSelected);
        btnDownload.setEnabled(false);
        panel.add(btnStartSync);
        panel.add(btnDownload);
        panel.add(retroButton("[ SYNC PRIVATE ]", FG_AMBER, this::syncPrivate));
        panel.add(retroButton("[ STOP SYNC ]", FG_AMBER, () -> stopSync(false)));
        panel.add(retroButton("[ STOP & DELETE ]", FG_RED, () -> stopSync(true)));
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
        // The default caret update policy auto-tracks appended text back to the caret whenever
        // the caret sits at the (old) end of the document, which fights our own auto-scroll
        // gating in appendLog() by yanking the view back down. Disable it — we manage the
        // caret/scroll position ourselves.
        ((DefaultCaret) logArea.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        JScrollPane scroll = retroScroll(logArea);
        logScrollBar = scroll.getVerticalScrollBar();
        return titledPanel("LOG", scroll);
    }

    // ── Host bar ──────────────────────────────────────────────────────────────

    private JPanel buildHostBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBackground(BG);
        bar.setBorder(new EmptyBorder(4, 12, 0, 12));

        bar.add(label("HOST ▸", MONO_SM, FG_DIM));

        hostField = new JTextField(18);
        hostField.setFont(MONO);
        hostField.setBackground(BG_CELL);
        hostField.setForeground(FG);
        hostField.setCaretColor(FG);
        hostField.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER_CLR, 1), new EmptyBorder(2, 4, 2, 4)));
        hostField.addActionListener(e -> switchHost());
        bar.add(hostField);

        JButton historyBtn = retroButton("▾", FG_AMBER, () -> showHostHistoryPopup(hostField));
        bar.add(historyBtn);

        bar.add(retroButton("[ CONNECT ]", FG_AMBER, this::switchHost));

        JSeparator sep = new JSeparator();
        sep.setForeground(BORDER_CLR);
        sep.setBackground(BG);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.add(sep, BorderLayout.NORTH);
        wrapper.add(bar, BorderLayout.CENTER);
        return wrapper;
    }

    private void switchHost() {
        String host = hostField.getText();
        if (host == null || host.isBlank()) {
            appendLog("Enter a host before connecting.");
            return;
        }
        // Drop the previous host's dirs up front rather than letting the refresh loop
        // diff them away later — otherwise they linger until the next successful poll,
        // and indefinitely if the new host shares none at all.
        treeListModel.clear();
        expandedKeys.clear();
        hostSettingsService.switchTo(host);
        appendLog("Switching to host: " + host.trim());
        refreshData();
    }

    /**
     * Shows a small popup listing previously-used hosts below {@code anchor}. Clicking
     * a host fills {@link #hostField}; clicking the "×" next to it removes it from the
     * history instead.
     */
    private void showHostHistoryPopup(JComponent anchor) {
        List<String> history = hostSettingsService.getHistory();
        if (history.isEmpty()) {
            appendLog("No previously used hosts yet.");
            return;
        }

        DefaultListModel<String> model = new DefaultListModel<>();
        history.forEach(model::addElement);

        JList<String> list = new JList<>(model);
        list.setBackground(BG_CELL);
        list.setFont(MONO);
        list.setFixedCellHeight(22);
        list.setCellRenderer(buildHostHistoryCellRenderer());

        JWindow popup = new JWindow(frame);
        popup.getContentPane().setBackground(BG_CELL);
        JScrollPane scroll = retroScroll(list);
        popup.getContentPane().add(scroll);

        final int deleteZonePx = 20;
        AWTEventListener[] outsideClickListener = new AWTEventListener[1];
        Runnable closePopup = () -> {
            popup.dispose();
            if (outsideClickListener[0] != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener[0]);
            }
        };

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index < 0) return;
                Rectangle bounds = list.getCellBounds(index, index);
                String host = model.getElementAt(index);
                if (e.getX() >= bounds.x + bounds.width - deleteZonePx) {
                    hostSettingsService.forget(host);
                    model.remove(index);
                    if (model.isEmpty()) {
                        closePopup.run();
                    } else {
                        popup.pack();
                    }
                } else {
                    hostField.setText(host);
                    closePopup.run();
                }
            }
        });

        outsideClickListener[0] = event -> {
            if (event instanceof MouseEvent me && me.getID() == MouseEvent.MOUSE_PRESSED) {
                Point screenPoint = me.getLocationOnScreen();
                if (!popup.getBounds().contains(screenPoint)) {
                    closePopup.run();
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(outsideClickListener[0], AWTEvent.MOUSE_EVENT_MASK);

        popup.pack();
        popup.setSize(Math.max(popup.getWidth(), anchor.getWidth()), popup.getHeight());
        Point anchorLoc = anchor.getLocationOnScreen();
        popup.setLocation(anchorLoc.x, anchorLoc.y + anchor.getHeight());
        popup.setVisible(true);
    }

    private ListCellRenderer<String> buildHostHistoryCellRenderer() {
        return (list, value, index, isSelected, cellHasFocus) -> {
            JPanel row = new JPanel(new BorderLayout());
            row.setBackground(isSelected ? BORDER_CLR : BG_CELL);
            row.setBorder(new EmptyBorder(2, 6, 2, 6));
            row.add(label(value, MONO, FG), BorderLayout.WEST);
            row.add(label("×", MONO_BOLD, FG_RED), BorderLayout.EAST);
            return row;
        };
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

    // ── Tree interactions ─────────────────────────────────────────────────────

    private void handleTreeSingleClick(MouseEvent e) {
        int index = treeList.locationToIndex(e.getPoint());
        if (index < 0) return;
        TreeItem item = treeListModel.getElementAt(index);
        treeList.setSelectedIndex(index);
        updateButtonStates(item);

        if (item.isFile()) return; // files are leaf nodes — nothing to expand

        String key = item.key();
        if (expandedKeys.contains(key)) {
            collapseTreeItem(index);
            expandedKeys.remove(key);
        } else {
            String subPath = item.isRootDir() ? "" : item.fullRelPath();
            String passwordHash = item.isRootDir() ? null : rootDirService.getPasswordHash(item.rootDirName());
            new SwingWorker<TreeResponse, Void>() {
                @Override
                protected TreeResponse doInBackground() {
                    return serverApiClient.fetchTree(item.rootDirName(), subPath, passwordHash);
                }

                @Override
                protected void done() {
                    try {
                        com.fstojilj.luddite.sync.common.dto.TreeResponse resp = get();
                        int insertAt = index + 1;
                        for (String dir : resp.childNames()) {
                            String childRel = item.isRootDir() ? dir : item.fullRelPath() + "/" + dir;
                            treeListModel.add(insertAt++, new TreeItem(item.rootDirName(), childRel, item.depth() + 1, false, false));
                        }
                        for (String file : resp.fileNames()) {
                            String childRel = item.isRootDir() ? file : item.fullRelPath() + "/" + file;
                            treeListModel.add(insertAt++, new TreeItem(item.rootDirName(), childRel, item.depth() + 1, false, true));
                        }
                        expandedKeys.add(key);
                    } catch (Exception ex) {
                        log.warn("Tree expand error", ex);
                    }
                }
            }.execute();
        }
    }

    private void collapseTreeItem(int index) {
        TreeItem item = treeListModel.getElementAt(index);
        int depth = item.depth();
        while (index + 1 < treeListModel.size()) {
            TreeItem next = treeListModel.getElementAt(index + 1);
            if (next.depth() > depth) {
                expandedKeys.remove(next.key());
                treeListModel.remove(index + 1);
            } else {
                break;
            }
        }
    }

    private void updateButtonStates(TreeItem item) {
        btnStartSync.setEnabled(item.isRootDir());
        btnDownload.setEnabled(true);
    }

    private void handleTreeDoubleClick(MouseEvent e) {
        int index = treeList.locationToIndex(e.getPoint());
        if (index < 0) return;
        TreeItem item = treeListModel.getElementAt(index);
        if (item.isRootDir()) {
            subscribeSelected();
        } else {
            downloadSelected();
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void subscribeSelected() {
        int idx = treeList.getSelectedIndex();
        if (idx < 0) {
            appendLog("[WARN] No server directory selected.");
            return;
        }
        TreeItem item = treeListModel.getElementAt(idx);
        if (!item.isRootDir()) {
            appendLog("[WARN] Select a root directory to subscribe.");
            return;
        }
        String dir = item.rootDirName();
        List<String> current = rootDirService.retrieveAllInSyncDirs();
        if (current.contains(dir)) {
            appendLog("[INFO] Already subscribed to: " + dir);
            return;
        }

        Optional<Path> chosen = showSubscribeLocationDialog(dir);
        if (chosen.isEmpty()) {
            appendLog("[INFO] Subscribe cancelled for: " + dir);
            return;
        }

        Path defaultPath = Path.of(mirrorDir).resolve(dir).normalize();
        Path selected = chosen.get().normalize();
        if (selected.equals(defaultPath)) {
            rootDirService.registerWithDefaultPath(dir);
        } else {
            rootDirService.registerWithCustomPath(dir, selected.toString());
        }
        appendLog("[OK]   Subscribed to: " + dir + " -> " + selected);
        refreshData();
    }

    /**
     * Shows a modal dialog letting the user choose where {@code dirName} should be
     * mirrored locally: the default {@code mirrorDir/dirName} location, or a custom
     * directory picked via a {@link JFileChooser}.
     *
     * @param dirName the server directory name being subscribed to
     * @return {@link Optional#empty()} if the user cancelled; otherwise the chosen
     * local path (default or custom)
     */
    private Optional<Path> showSubscribeLocationDialog(String dirName) {
        Path defaultPath = Path.of(mirrorDir).resolve(dirName);

        JRadioButton defaultRadio = new JRadioButton("DEFAULT: " + defaultPath, true);
        JRadioButton customRadio = new JRadioButton("CUSTOM:");
        styleRadio(defaultRadio);
        styleRadio(customRadio);
        ButtonGroup group = new ButtonGroup();
        group.add(defaultRadio);
        group.add(customRadio);

        JTextField customField = new JTextField(22);
        customField.setBackground(new Color(0x1A, 0x1A, 0x1A));
        customField.setForeground(FG);
        customField.setCaretColor(FG);
        customField.setFont(MONO_SM);
        customField.setBorder(new LineBorder(BORDER_CLR, 1));
        customField.setEnabled(false);

        JButton browseBtn = retroButton("[ BROWSE... ]", FG_AMBER, () -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("SELECT SYNC LOCATION");
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                // Mirror into a subfolder named after the server dir, as the DEFAULT
                customField.setText(chooser.getSelectedFile().toPath().resolve(dirName).toString());
            }
        });
        browseBtn.setEnabled(false);

        defaultRadio.addActionListener(e -> {
            customField.setEnabled(false);
            browseBtn.setEnabled(false);
        });
        customRadio.addActionListener(e -> {
            customField.setEnabled(true);
            browseBtn.setEnabled(true);
        });

        JPanel customRow = new JPanel(new BorderLayout(4, 0));
        customRow.setBackground(BG);
        customRow.add(customField, BorderLayout.CENTER);
        customRow.add(browseBtn, BorderLayout.EAST);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.add(label("SYNC LOCATION FOR: " + dirName, MONO_SM, FG_DIM));
        content.add(Box.createVerticalStrut(6));
        content.add(defaultRadio);
        content.add(Box.createVerticalStrut(4));
        content.add(customRadio);
        content.add(customRow);

        // --- custom button row, replacing JOptionPane's default OK/Cancel ---
        final boolean[] confirmed = {false};

        JDialog dialog = new JDialog(frame, "SUBSCRIBE: " + dirName, true);
        dialog.setUndecorated(true);
        dialog.getContentPane().setBackground(BG);
        dialog.getRootPane().setBorder(new LineBorder(BORDER_CLR, 1));
        dialog.getRootPane().setBackground(BG);

        JButton okBtn = retroButton("OK", FG, () -> {
            confirmed[0] = true;
            dialog.dispose();
        });
        JButton cancelBtn = retroButton("Cancel", FG, dialog::dispose);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttonRow.setBackground(BG);
        buttonRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        buttonRow.add(okBtn);
        buttonRow.add(cancelBtn);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(buildDialogTitleBar(dialog, "SUBSCRIBE: " + dirName, dialog::dispose), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setResizable(false);
        dialog.setVisible(true); // blocks until dispose()

        if (!confirmed[0]) return Optional.empty();

        if (customRadio.isSelected()) {
            String text = customField.getText().trim();
            if (!text.isEmpty()) {
                return Optional.of(Path.of(text));
            }
        }
        return Optional.of(defaultPath);
    }

    private static void styleRadio(JRadioButton radio) {
        radio.setFont(MONO_SM);
        radio.setForeground(FG);
        radio.setBackground(BG);
        radio.setFocusPainted(false);
    }

    private void downloadSelected() {
        int idx = treeList.getSelectedIndex();
        if (idx < 0) {
            appendLog("[WARN] No server item selected.");
            return;
        }
        TreeItem item = treeListModel.getElementAt(idx);
        String dirName = item.rootDirName();
        String subPath = item.isRootDir() ? "" : item.fullRelPath();
        boolean isFile = item.isFile();

        appendLog("[INFO] Downloading: " + item.key() + " ...");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                return downloadService.download(dirName, subPath, isFile);
            }

            @Override
            protected void done() {
                try {
                    int count = get();
                    if (count > 0) {
                        appendLog("[OK]   Downloaded " + count + " file(s): " + item.key());
                    } else {
                        appendLog("[WARN] Download failed or found no files: " + item.key());
                    }
                } catch (Exception ex) {
                    log.warn("downloadSelected result check error", ex);
                    appendLog("[WARN] Download error: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void stopSync(boolean deleteLocalFiles) {
        String dir = subscribedList.getSelectedValue();
        if (dir == null) {
            appendLog("[WARN] No subscribed directory selected.");
            return;
        }
        String msg = deleteLocalFiles
                ? "Stop sync AND delete local files for <b>" + dir + "</b>?"
                : "Stop sync for <b>" + dir + "</b>?<br>Local mirror files will be kept.";
        boolean confirm = showRetroConfirm(
                deleteLocalFiles ? "CONFIRM STOP & DELETE" : "CONFIRM STOP SYNC",
                retroHtmlMsg(msg));
        if (!confirm) return;

        rootDirService.removeDirectory(dir, deleteLocalFiles);
        appendLog(deleteLocalFiles
                ? "[OK]   Stopped sync and deleted local files for: " + dir
                : "[OK]   Stopped sync (local files kept) for: " + dir);
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

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.add(inputPanel, BorderLayout.CENTER);

        final boolean[] confirmed = {false};

        JDialog dialog = new JDialog(frame, "SYNC PRIVATE DIRECTORY", true);
        dialog.setUndecorated(true);
        dialog.getContentPane().setBackground(BG);
        dialog.getRootPane().setBorder(new LineBorder(BORDER_CLR, 1));
        dialog.getRootPane().setBackground(BG);

        JButton okBtn = retroButton("OK", FG, () -> {
            confirmed[0] = true;
            dialog.dispose();
        });
        JButton cancelBtn = retroButton("Cancel", FG, dialog::dispose);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttonRow.setBackground(BG);
        buttonRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        buttonRow.add(okBtn);
        buttonRow.add(cancelBtn);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(buildDialogTitleBar(dialog, "SYNC PRIVATE DIRECTORY", dialog::dispose), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setResizable(false);
        dialog.setVisible(true); // blocks until dispose()

        if (!confirmed[0]) return;

        String dirName = dirField.getText().trim();
        String password = new String(passField.getPassword());

        if (dirName.isEmpty() || password.isEmpty()) {
            appendLog("[WARN] Directory name and password must not be empty.");
            return;
        }

        appendLog("[INFO] Requesting access to private dir: " + dirName);
        clientSyncService.requestPrivateDir(dirName, password);

        // Poll the auth result after a short delay to let the reconnect complete
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                // Wait long enough for the reconnect + auth round-trip (max 8 s)
                for (int i = 0; i < 16; i++) {
                    Thread.sleep(500);
                    Map<String, Boolean> results = clientSyncService.getPrivateAuthResults();
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
                        showRetroMessage("ACCESS DENIED",
                                retroHtmlMsg("<span style='color:#FF4444'>ACCESS DENIED<br>"
                                        + "Wrong directory name or password.</span>"));
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
        boolean confirm = showRetroConfirm("CONFIRM EXIT", retroHtmlMsg("Exit Luddite Sync client?"));
        if (!confirm) return;
        refreshTimer.stop();
        frame.dispose();
        SpringApplication.exit(applicationContext, () -> 0);
    }

    /**
     * Shows a modal Yes/No confirmation dialog styled to match the retro-terminal theme,
     * replacing {@link JOptionPane#showConfirmDialog} (which draws native OS chrome that
     * clashes with the rest of the UI).
     *
     * @param title    dialog title-bar text
     * @param htmlBody HTML-formatted message body, see {@link #retroHtmlMsg(String)}
     * @return {@code true} if the user chose "Yes"; {@code false} for "No" or if the
     * dialog was dismissed via its close button
     */
    private boolean showRetroConfirm(String title, String htmlBody) {
        final boolean[] confirmed = {false};

        JDialog dialog = new JDialog(frame, title, true);
        dialog.setUndecorated(true);
        dialog.getContentPane().setBackground(BG);
        dialog.getRootPane().setBorder(new LineBorder(BORDER_CLR, 1));
        dialog.getRootPane().setBackground(BG);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        content.add(new JLabel(htmlBody), BorderLayout.CENTER);

        JButton yesBtn = retroButton("Yes", FG, () -> {
            confirmed[0] = true;
            dialog.dispose();
        });
        JButton noBtn = retroButton("No", FG, dialog::dispose);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttonRow.setBackground(BG);
        buttonRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        buttonRow.add(yesBtn);
        buttonRow.add(noBtn);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(buildDialogTitleBar(dialog, title, dialog::dispose), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setResizable(false);
        dialog.setVisible(true); // blocks until dispose()

        return confirmed[0];
    }

    /**
     * Shows a modal message dialog styled to match the retro-terminal theme, replacing
     * {@link JOptionPane#showMessageDialog} (which draws native OS chrome that clashes with
     * the rest of the UI). Dismissed via its OK button or the title bar's close control.
     *
     * @param title    dialog title-bar text
     * @param htmlBody HTML-formatted message body, see {@link #retroHtmlMsg(String)}
     */
    private void showRetroMessage(String title, String htmlBody) {
        JDialog dialog = new JDialog(frame, title, true);
        dialog.setUndecorated(true);
        dialog.getContentPane().setBackground(BG);
        dialog.getRootPane().setBorder(new LineBorder(BORDER_CLR, 1));
        dialog.getRootPane().setBackground(BG);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        content.add(new JLabel(htmlBody), BorderLayout.CENTER);

        JButton okBtn = retroButton("OK", FG, dialog::dispose);
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttonRow.setBackground(BG);
        buttonRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        buttonRow.add(okBtn);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(buildDialogTitleBar(dialog, title, dialog::dispose), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setResizable(false);
        dialog.setVisible(true); // blocks until dispose()
    }

    /**
     * Fills {@link #hostField} with the resolved host, once. Deliberately kept off the
     * {@link #refreshData()} SwingWorker pipeline: that pipeline calls
     * {@code serverApiClient.fetchPublicDirs()}, a network call to the actual server
     * that can block for a long time (or indefinitely) if the server is unreachable —
     * which would otherwise starve the host field of ever being populated. This method
     * only reads {@code socketFactory.getServerHost()} (an in-memory field, no I/O), so
     * it's safe to call directly on the EDT every tick until it succeeds. Runs before
     * the very first {@code refreshData()} call — {@code HostSettingsInitializer}
     * resolves the host slightly after UI-build time, on the main thread, so this
     * retries each tick rather than assuming it's ready on the first one.
     */
    private void populateHostFieldOnce() {
        if (hostFieldPopulated) return;
        String host = hostSettingsService.getCurrentHost();
        if (host != null && !host.isBlank()) {
            hostField.setText(host);
            hostFieldPopulated = true;
        }
    }

    // ── Data refresh (DB work off EDT via SwingWorker) ─────────────────────────

    private void refreshData() {
        if (refreshInFlight) return;
        refreshInFlight = true;
        new SwingWorker<RefreshSnapshot, Void>() {
            @Override
            protected RefreshSnapshot doInBackground() {
                List<String> srv = serverApiClient.fetchPublicDirs();
                List<String> subs = rootDirService.retrieveAllInSyncDirs();
                ConnectionState state = clientSyncService.getConnectionState();
                return new RefreshSnapshot(srv, subs, state);
            }

            @Override
            protected void done() {
                try {
                    RefreshSnapshot snap = get();

                    // Update left panel: add new root dirs, remove gone ones
                    Set<String> currentRoots = new HashSet<>();
                    for (int i = 0; i < treeListModel.size(); i++) {
                        TreeItem item = treeListModel.getElementAt(i);
                        if (item.isRootDir()) currentRoots.add(item.rootDirName());
                    }
                    for (String dir : snap.serverDirs()) {
                        if (!currentRoots.contains(dir)) {
                            treeListModel.addElement(new TreeItem(dir, dir, 0, true, false));
                        }
                    }
                    Set<String> newRoots = new HashSet<>(snap.serverDirs());
                    for (int i = treeListModel.size() - 1; i >= 0; i--) {
                        TreeItem item = treeListModel.getElementAt(i);
                        if (item.isRootDir() && !newRoots.contains(item.rootDirName())) {
                            collapseTreeItem(i);
                            treeListModel.remove(i);
                            expandedKeys.remove(item.key());
                        }
                    }

                    // Update right panel
                    long now = System.currentTimeMillis();
                    int selectedIdx = subscribedList.getSelectedIndex();
                    String selectedVal = selectedIdx >= 0 ? subscribedListModel.getElementAt(selectedIdx) : null;
                    subscribedListModel.clear();
                    snap.subscribedDirs().forEach(subscribedListModel::addElement);
                    if (selectedVal != null && now - lastSubscribedClickMs < 3000) {
                        int newIdx = subscribedListModel.indexOf(selectedVal);
                        if (newIdx >= 0) subscribedList.setSelectedIndex(newIdx);
                    }

                    Color dotColor = switch (snap.connectionState()) {
                        case TRANSFERRING, IDLE -> FG;
                        case DISCONNECTED -> FG_RED;
                    };
                    statusDot.setForeground(dotColor);
                    statusLabel.setForeground(dotColor);
                    statusLabel.setText(snap.connectionState().name());
                } catch (Exception ex) {
                    log.warn("refreshData error", ex);
                } finally {
                    refreshInFlight = false;
                }
            }
        }.execute();
    }

    private record RefreshSnapshot(List<String> serverDirs,
                                   List<String> subscribedDirs,
                                   ConnectionState connectionState) {
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void appendLog(String msg) {
        String line = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg + "\n";
        SwingUtilities.invokeLater(() -> {
            if (logArea == null) return;   // UI not yet built
            boolean stickToBottom = isLogScrolledToBottom();
            logArea.append(line);
            if (stickToBottom) {
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    /**
     * Whether the log viewport is currently scrolled to (or within a few pixels of) the bottom.
     * Used to decide whether a newly appended line should auto-scroll the view — so a user who
     * has scrolled up to read older entries isn't yanked back down by incoming log lines.
     */
    private boolean isLogScrolledToBottom() {
        if (logScrollBar == null) return true;
        int extent = logScrollBar.getModel().getExtent();
        return logScrollBar.getValue() + extent >= logScrollBar.getMaximum() - LOG_AUTOSCROLL_SLACK_PX;
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
                    if (buf.size() > 0) {
                        String line = buf.toString(StandardCharsets.UTF_8);
                        buf.reset();
                        if (!line.isBlank()) appendLog(line);
                    }
                } else if (b != '\r') {
                    buf.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                for (int i = off; i < off + len; i++) write(b[i] & 0xFF);
            }

            @Override
            public void flush() {
                // Intentional no-op — autoFlush on PrintStream calls flush() after every
                // sub-write; logging here would split one logical line into fragments.
                // Only complete lines (terminated by \n) are logged, via write(int b).
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
                Color base = isEnabled() ? BG_CELL : BG_CELL.darker();
                g2.setColor(getModel().isPressed() ? fg.darker() : base);
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
                if (!btn.isEnabled()) return;
                btn.setBorder(new LineBorder(fg, 1));
                btn.setForeground(fg.brighter());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!btn.isEnabled()) return;
                btn.setBorder(new LineBorder(fg.darker(), 1));
                btn.setForeground(fg);
            }
        });
        btn.addActionListener(e -> action.run());
        return btn;
    }

    /**
     * A small square {@code retroButton} sized for use as a window-control (minimize/close)
     * icon in a custom title bar, e.g. {@code windowControlButton("X", FG_RED, window::dispose)}.
     */
    private static JButton windowControlButton(String symbol, Color fg, Runnable action) {
        JButton btn = retroButton(symbol, fg, action);
        btn.setMargin(new java.awt.Insets(0, 0, 0, 0));
        btn.setPreferredSize(new Dimension(24, 20));
        return btn;
    }

    /**
     * Makes {@code window} draggable by press-and-drag on {@code dragHandle}. Needed because
     * undecorated frames/dialogs lose the OS's built-in title-bar drag behavior.
     *
     * <p>Swing dispatches mouse events to the topmost component under the cursor rather than
     * bubbling them to ancestors, so this must be attached to every visible component that
     * makes up the draggable region (e.g. both the title bar panel and its title label),
     * not just the outermost container.
     */
    private static void enableWindowDrag(JComponent dragHandle, Window window) {
        MouseAdapter dragListener = new MouseAdapter() {
            private Point dragOrigin;

            @Override
            public void mousePressed(MouseEvent e) {
                dragOrigin = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOrigin == null) return;
                Point loc = window.getLocation();
                window.setLocation(loc.x + e.getX() - dragOrigin.x, loc.y + e.getY() - dragOrigin.y);
            }
        };
        dragHandle.addMouseListener(dragListener);
        dragHandle.addMouseMotionListener(dragListener);
    }

    /**
     * Wraps {@code content} in a panel with a {@link #RESIZE_MARGIN}-pixel invisible hit-test
     * ring that lets the user resize {@code frame} by dragging its edges/corners, with only a
     * thin {@link #VISIBLE_BORDER_PX}-pixel {@code BORDER_CLR} line actually painted at the
     * outer edge as the frame's outline (replacing the plain {@code LineBorder} used before,
     * since undecorated frames have no OS-drawn border either).
     *
     * <p>Needed because undecorated frames lose the OS's built-in edge/corner drag-to-resize
     * behavior. The margin ring around {@code content} has no child component occupying it, so
     * mouse events landing there are delivered to this wrapper panel's own listeners rather
     * than being swallowed by a child — the same principle {@link #enableWindowDrag} relies on.
     */
    private static JPanel wrapWithResizeBorder(JFrame frame, JComponent content, Dimension minSize) {
        JPanel wrapper = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(BG);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(BORDER_CLR);
                g.fillRect(0, 0, getWidth(), VISIBLE_BORDER_PX);
                g.fillRect(0, getHeight() - VISIBLE_BORDER_PX, getWidth(), VISIBLE_BORDER_PX);
                g.fillRect(0, 0, VISIBLE_BORDER_PX, getHeight());
                g.fillRect(getWidth() - VISIBLE_BORDER_PX, 0, VISIBLE_BORDER_PX, getHeight());
            }
        };
        wrapper.setOpaque(true);
        wrapper.setBorder(new EmptyBorder(RESIZE_MARGIN, RESIZE_MARGIN, RESIZE_MARGIN, RESIZE_MARGIN));
        wrapper.add(content, BorderLayout.CENTER);

        ResizeController controller = new ResizeController(frame, wrapper, RESIZE_MARGIN, minSize);
        wrapper.addMouseListener(controller);
        wrapper.addMouseMotionListener(controller);
        return wrapper;
    }

    /**
     * Drives edge/corner drag-to-resize for an undecorated {@link JFrame}, attached to the
     * margin ring built by {@link #wrapWithResizeBorder}. Tracks which edge(s) the cursor is
     * over (as a bitmask of {@link #NORTH}/{@link #SOUTH}/{@link #WEST}/{@link #EAST}) to show
     * the right resize cursor and, while dragging, to grow/shrink the frame from the correct
     * side(s) — combining two edges handles the four corners.
     */
    private static final class ResizeController extends MouseAdapter {
        private static final int NORTH = 1;
        private static final int SOUTH = 2;
        private static final int WEST = 4;
        private static final int EAST = 8;

        private final JFrame frame;
        private final JComponent handle;
        private final int margin;
        private final Dimension minSize;
        private int zone;
        private Point pressScreenPoint;
        private Rectangle pressBounds;

        ResizeController(JFrame frame, JComponent handle, int margin, Dimension minSize) {
            this.frame = frame;
            this.handle = handle;
            this.margin = margin;
            this.minSize = minSize;
        }

        private int zoneAt(Point p) {
            int z = 0;
            if (p.y <= margin) z |= NORTH;
            else if (p.y >= handle.getHeight() - margin) z |= SOUTH;
            if (p.x <= margin) z |= WEST;
            else if (p.x >= handle.getWidth() - margin) z |= EAST;
            return z;
        }

        private Cursor cursorFor(int z) {
            return switch (z) {
                case NORTH -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
                case SOUTH -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
                case WEST -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
                case EAST -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
                case NORTH | WEST -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
                case NORTH | EAST -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
                case SOUTH | WEST -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
                case SOUTH | EAST -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
                default -> Cursor.getDefaultCursor();
            };
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            handle.setCursor(cursorFor(zoneAt(e.getPoint())));
        }

        @Override
        public void mouseExited(MouseEvent e) {
            handle.setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mousePressed(MouseEvent e) {
            zone = zoneAt(e.getPoint());
            pressScreenPoint = e.getLocationOnScreen();
            pressBounds = frame.getBounds();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (zone == 0) return;
            Point nowScreen = e.getLocationOnScreen();
            int dx = nowScreen.x - pressScreenPoint.x;
            int dy = nowScreen.y - pressScreenPoint.y;

            int x = pressBounds.x, y = pressBounds.y, w = pressBounds.width, h = pressBounds.height;
            if ((zone & WEST) != 0) {
                int newW = Math.max(minSize.width, w - dx);
                x += w - newW;
                w = newW;
            } else if ((zone & EAST) != 0) {
                w = Math.max(minSize.width, w + dx);
            }
            if ((zone & NORTH) != 0) {
                int newH = Math.max(minSize.height, h - dy);
                y += h - newH;
                h = newH;
            } else if ((zone & SOUTH) != 0) {
                h = Math.max(minSize.height, h + dy);
            }
            frame.setBounds(x, y, w, h);
        }
    }

    /**
     * Builds a themed, draggable title-bar panel for an undecorated popup dialog, replacing
     * the OS-native title heading with one that matches the retro-terminal theme. Only a
     * {@code [X]} close control is provided — popups don't need minimize.
     */
    private static JPanel buildDialogTitleBar(Window window, String title, Runnable onClose) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG);
        bar.setBorder(new EmptyBorder(4, 8, 4, 4));

        JLabel titleLabel = label(title, MONO_BOLD, FG);
        // Extra right padding widens this row's minimum preferred width (BorderLayout sizes a
        // WEST/EAST-only row to west.width + east.width with no gap of its own), which in turn
        // widens the whole dialog on pack() — giving breathing room instead of the title text
        // and close button sitting flush against each other.
        titleLabel.setBorder(new EmptyBorder(0, 0, 0, 18));
        bar.add(titleLabel, BorderLayout.WEST);
        bar.add(windowControlButton("X", FG_RED, onClose), BorderLayout.EAST);

        JSeparator sep = new JSeparator();
        sep.setForeground(BORDER_CLR);
        sep.setBackground(BG);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.add(bar, BorderLayout.CENTER);
        wrapper.add(sep, BorderLayout.SOUTH);

        enableWindowDrag(bar, window);
        enableWindowDrag(titleLabel, window);
        return wrapper;
    }

    private static JScrollPane retroScroll(JComponent view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBackground(BG_CELL);
        sp.setBorder(new LineBorder(BORDER_CLR, 1));
        sp.getViewport().setBackground(BG_CELL);
        styleScrollBar(sp.getVerticalScrollBar());
        styleScrollBar(sp.getHorizontalScrollBar());
        return sp;
    }

    /**
     * Replaces a scrollbar's look-and-feel-supplied UI (which paints a native white/gray track
     * and arrow buttons regardless of {@code setBackground}) with a flat, borderless retro style
     * consistent with the rest of the theme.
     */
    private static void styleScrollBar(JScrollBar bar) {
        bar.setPreferredSize(new Dimension(10, 10));
        bar.setUnitIncrement(16);
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = BORDER_CLR;
                trackColor = BG_CELL;
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return zeroSizeButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return zeroSizeButton();
            }

            private JButton zeroSizeButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setMinimumSize(new Dimension(0, 0));
                button.setMaximumSize(new Dimension(0, 0));
                return button;
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
                g.setColor(BG_CELL);
                g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                if (thumbBounds.isEmpty() || !c.isEnabled()) return;
                g.setColor(BORDER_CLR);
                g.fillRect(thumbBounds.x + 1, thumbBounds.y + 1, thumbBounds.width - 2, thumbBounds.height - 2);
            }
        });
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

    private static String retroHtmlMsg(String body) {
        return "<html><body style='font-family:Courier New;font-size:12px;color:#00E540;"
                + "background:#0D0D0D;padding:8px'>" + body + "</body></html>";
    }
}









package com.fstojilj.luddite.sync.client.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Retro-terminal palette and widget factories shared by {@link ClientUI} and {@link UiModeDialog}.
 */
final class RetroTheme {

    static final Color BG = new Color(0x0D, 0x0D, 0x0D);
    static final Color BG_PANEL = new Color(0x13, 0x13, 0x13);
    static final Color BG_CELL = new Color(0x1A, 0x1A, 0x1A);
    static final Color FG = new Color(0x00, 0xE5, 0x40);   // matrix green
    static final Color FG_DIM = new Color(0x00, 0x80, 0x25);
    static final Color FG_AMBER = new Color(0xFF, 0xB0, 0x00);   // amber accent
    static final Color FG_RED = new Color(0xFF, 0x44, 0x44);
    static final Color BORDER_CLR = new Color(0x00, 0x66, 0x1A);
    static final Font MONO_BOLD = new Font("Courier New", Font.BOLD, 13);
    static final Font MONO = new Font("Courier New", Font.PLAIN, 12);
    static final Font MONO_SM = new Font("Courier New", Font.PLAIN, 11);

    private RetroTheme() {
    }

    static JLabel label(String text, Font font, Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(fg);
        l.setOpaque(false);
        return l;
    }

    static JButton retroButton(String text, Color fg, Runnable action) {
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
    static JButton windowControlButton(String symbol, Color fg, Runnable action) {
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
    static void enableWindowDrag(JComponent dragHandle, Window window) {
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
     * Builds a themed, draggable title-bar panel for an undecorated popup dialog, replacing
     * the OS-native title heading with one that matches the retro-terminal theme. Only a
     * {@code [X]} close control is provided — popups don't need minimize.
     */
    static JPanel buildDialogTitleBar(Window window, String title, Runnable onClose) {
        return buildDialogTitleBar(window, title, null, onClose);
    }

    /**
     * Same as {@link #buildDialogTitleBar(Window, String, Runnable)} with an additional
     * {@code [_]} minimize control when {@code onMinimize} is non-null.
     */
    static JPanel buildDialogTitleBar(Window window, String title, Runnable onMinimize, Runnable onClose) {
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

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setBackground(BG);
        if (onMinimize != null) {
            controls.add(windowControlButton("_", FG_DIM, onMinimize));
        }
        controls.add(windowControlButton("X", FG_RED, onClose));
        bar.add(controls, BorderLayout.EAST);

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
}

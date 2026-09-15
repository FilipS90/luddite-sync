package com.fstojilj.luddite.sync.client.ui;

import com.fstojilj.luddite.sync.client.ui.UiModeResolver.PromptResult;
import lombok.experimental.UtilityClass;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.fstojilj.luddite.sync.client.ui.RetroTheme.BG;
import static com.fstojilj.luddite.sync.client.ui.RetroTheme.BORDER_CLR;
import static com.fstojilj.luddite.sync.client.ui.RetroTheme.FG;
import static com.fstojilj.luddite.sync.client.ui.RetroTheme.MONO;
import static com.fstojilj.luddite.sync.client.ui.RetroTheme.MONO_BOLD;
import static com.fstojilj.luddite.sync.client.ui.RetroTheme.buildDialogTitleBar;
import static com.fstojilj.luddite.sync.client.ui.RetroTheme.label;
import static com.fstojilj.luddite.sync.client.ui.RetroTheme.retroButton;

/**
 * Start-up window asking whether to run the desktop UI or the CLI, styled like the
 * {@link ClientUI} popups. Closing it counts as choosing the desktop UI without remembering.
 * A frame rather than a dialog so the title bar's minimize control works.
 */
@UtilityClass
public class UiModeDialog {

    public static PromptResult prompt() {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<UiMode> choice = new AtomicReference<>();
        AtomicReference<JCheckBox> remember = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> remember.set(show(choice, done)));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("UI mode prompt interrupted", e);
        }
        UiMode picked = choice.get();
        if (picked == null) {
            return new PromptResult(UiMode.SWING, false);
        }
        return new PromptResult(picked, remember.get().isSelected());
    }

    private static JCheckBox show(AtomicReference<UiMode> choice, CountDownLatch done) {
        JFrame frame = new JFrame("Luddite Sync");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.getRootPane().setBorder(new LineBorder(BORDER_CLR, 1));
        frame.getRootPane().setBackground(BG);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                done.countDown();
            }
        });

        JCheckBox remember = new JCheckBox("Don't ask again for the next "
                + UiModeResolver.REMEMBER_LAUNCHES + " launches");
        remember.setFont(MONO);
        remember.setForeground(FG);
        remember.setBackground(BG);
        remember.setFocusPainted(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttons.setBackground(BG);
        buttons.add(choiceButton("Desktop UI", UiMode.SWING, choice, frame));
        buttons.add(choiceButton("CLI", UiMode.CLI, choice, frame));

        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 14, 20));
        content.add(label("How do you want to run the client?", MONO_BOLD, FG), BorderLayout.NORTH);
        content.add(buttons, BorderLayout.CENTER);
        content.add(remember, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(buildDialogTitleBar(frame, "LUDDITE SYNC",
                () -> frame.setExtendedState(Frame.ICONIFIED), frame::dispose), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.pack();
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        return remember;
    }

    private static JButton choiceButton(String text, UiMode mode, AtomicReference<UiMode> choice, JFrame frame) {
        JButton button = retroButton(text, FG, () -> {
            choice.set(mode);
            frame.dispose();
        });
        Dimension size = button.getPreferredSize();
        button.setPreferredSize(new Dimension(size.width + 28, size.height + 10));
        return button;
    }
}

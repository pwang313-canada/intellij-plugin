package org.cakk.memoryleak.ui;


import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class LeakSimulationDialog extends DialogWrapper {

  private final Project project;
  private final JTextArea outputArea;
  private final JButton simulateButton;
  private final AtomicBoolean isSimulating = new AtomicBoolean(false);

  public LeakSimulationDialog(Project project) {
    super(project);
    this.project = project;
    this.outputArea = new JTextArea();
    this.simulateButton = new JButton("Start Simulation");

    setTitle("Memory Leak Simulation");
    setSize(700, 500);
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(10));

    // Description
    JTextArea description = new JTextArea(
            "This simulation demonstrates a memory leak by:\n" +
                    "1. Creating objects that are never released\n" +
                    "2. Showing how GC cannot reclaim them\n" +
                    "3. Demonstrating weak references vs strong references\n\n" +
                    "Watch the Memory Monitor tool window to see old generation growth."
    );
    description.setEditable(false);
    description.setOpaque(false);
    description.setWrapStyleWord(true);
    description.setLineWrap(true);
    panel.add(description, BorderLayout.NORTH);

    // Output area
    outputArea.setEditable(false);
    outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    JScrollPane scrollPane = new JScrollPane(outputArea);
    scrollPane.setBorder(JBUI.Borders.empty(5));
    panel.add(scrollPane, BorderLayout.CENTER);

    // Button panel
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
    simulateButton.addActionListener(e -> startSimulation());
    buttonPanel.add(simulateButton);
    panel.add(buttonPanel, BorderLayout.SOUTH);

    return panel;
  }

  private void startSimulation() {
    if (isSimulating.compareAndSet(false, true)) {
      simulateButton.setEnabled(false);
      outputArea.setText("");

      ProgressManager.getInstance().run(new Task.Backgroundable(project, "Memory Leak Simulation", true) {
        @Override
        public void run(ProgressIndicator indicator) {
          runLeakSimulation(indicator);
          isSimulating.set(false);
          SwingUtilities.invokeLater(() -> simulateButton.setEnabled(true));
        }
      });
    }
  }

  private void runLeakSimulation(ProgressIndicator indicator) {
    List<byte[]> leakyList = new ArrayList<>();
    List<java.lang.ref.WeakReference<byte[]>> weakRefs = new ArrayList<>();

    try {
      // Phase 1: Normal allocation
      appendOutput("[Phase 1] Normal allocations (no leak)");
      for (int i = 0; i < 10; i++) {
        indicator.checkCanceled();
        byte[] temp = new byte[5 * 1024 * 1024];
        Thread.sleep(1000);
        appendOutput("  Allocated 5MB (will be GC'd)");
      }

      // Phase 2: Creating memory leak
      appendOutput("\n[Phase 2] Creating memory leak - adding to list");
      for (int i = 0; i < 30; i++) {
        indicator.checkCanceled();
        byte[] leak = new byte[10 * 1024 * 1024];
        leakyList.add(leak);
        appendOutput(String.format("  Added %d MB, total leak: %d MB",
                (i + 1) * 10, leakyList.size() * 10));
        Thread.sleep(500);

        if ((i + 1) % 5 == 0) {
          System.gc();
          appendOutput("  ** GC called - but leak persists! **");
          Thread.sleep(1000);
        }
      }

      // Phase 3: Weak references
      appendOutput("\n[Phase 3] Creating weak references");
      for (int i = 0; i < 20; i++) {
        indicator.checkCanceled();
        byte[] temp = new byte[5 * 1024 * 1024];
        weakRefs.add(new java.lang.ref.WeakReference<>(temp));
        Thread.sleep(200);
      }

      // Phase 4: Show weak references cleared
      appendOutput("\n[Phase 4] Forcing GC to show weak references cleared");
      System.gc();
      Thread.sleep(2000);

      int clearedWeak = 0;
      for (java.lang.ref.WeakReference<byte[]> ref : weakRefs) {
        if (ref.get() == null) clearedWeak++;
      }
      appendOutput(String.format("  Weak references cleared: %d/%d", clearedWeak, weakRefs.size()));
      appendOutput("  BUT leaky list objects REMAIN in old gen!");

      // Phase 5: Continue leak
      appendOutput("\n[Phase 5] Continuing leak growth");
      for (int i = 0; i < 20; i++) {
        indicator.checkCanceled();
        byte[] leak = new byte[10 * 1024 * 1024];
        leakyList.add(leak);
        appendOutput(String.format("  Leak now: %d MB total", leakyList.size() * 10));
        Thread.sleep(500);
      }

      appendOutput("\n⚠️  Memory leak simulation complete!");
      appendOutput("Notice: Old gen keeps growing, GC cannot reclaim leaked objects");

    } catch (InterruptedException e) {
      appendOutput("\nSimulation interrupted");
      Thread.currentThread().interrupt();
    }
  }

  private void appendOutput(String text) {
    SwingUtilities.invokeLater(() -> {
      outputArea.append(text + "\n");
      outputArea.setCaretPosition(outputArea.getDocument().getLength());
    });
  }
}
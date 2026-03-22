// src/main/java/org/cakk/memoryleak/ui/MemoryMonitorToolWindow.java
package org.cakk.memoryleak.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MemoryMonitorToolWindow extends SimpleToolWindowPanel {

  private final Project project;
  private final MemoryMonitorService monitorService;
  private final MemoryChartPanel chartPanel;
  private final StatisticsPanel statsPanel;
  private final JTextArea logPanel;
  private final JLabel statusLabel;
  private final JButton startButton;
  private final JButton stopButton;
  private final JButton simulateButton;
  private final JButton gcButton;
  private final JButton clearLogButton;

  private ScheduledExecutorService uiUpdater;

  public MemoryMonitorToolWindow(@NotNull Project project) {
    super(true, true);
    this.project = project;
    this.monitorService = project.getService(MemoryMonitorService.class);
    this.chartPanel = new MemoryChartPanel();
    this.statsPanel = new StatisticsPanel();
    this.logPanel = new JTextArea();
    this.statusLabel = new JLabel("● Stopped");
    this.startButton = new JButton("Start");
    this.stopButton = new JButton("Stop");
    this.simulateButton = new JButton("Simulate Leak");
    this.gcButton = new JButton("Run GC");
    this.clearLogButton = new JButton("Clear Log");

    initUI();
    setupListeners();
    startUIUpdater();
  }

  private void initUI() {
    setLayout(new BorderLayout());

    // Toolbar
    JToolBar toolbar = createToolbar();
    add(toolbar, BorderLayout.NORTH);

    // Main content - Split pane with chart on top, log at bottom
    JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    mainSplitPane.setTopComponent(createTopPanel());
    mainSplitPane.setBottomComponent(createBottomPanel());
    mainSplitPane.setResizeWeight(0.7);
    mainSplitPane.setDividerLocation(400);
    add(mainSplitPane, BorderLayout.CENTER);

    // Status bar
    JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
    statusBar.setBorder(JBUI.Borders.empty(2, 5, 2, 5));
    statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
    statusBar.add(statusLabel);
    add(statusBar, BorderLayout.SOUTH);
  }

  private JToolBar createToolbar() {
    JToolBar toolbar = new JToolBar();
    toolbar.setFloatable(false);

    startButton.setIcon(UIManager.getIcon("AllIcons.Actions.Execute"));
    startButton.addActionListener(e -> {
      monitorService.startMonitoring();
      updateButtonState();
    });

    stopButton.setIcon(UIManager.getIcon("AllIcons.Actions.Suspend"));
    stopButton.setEnabled(false);
    stopButton.addActionListener(e -> {
      monitorService.stopMonitoring();
      updateButtonState();
    });

    simulateButton.setIcon(UIManager.getIcon("AllIcons.General.Warning"));
    simulateButton.addActionListener(e -> {
      LeakSimulationDialog dialog = new LeakSimulationDialog(project);
      dialog.show();
    });

    gcButton.setIcon(UIManager.getIcon("AllIcons.Actions.GC"));
    gcButton.addActionListener(e -> {
      monitorService.forceGc();
      appendLog("[Manual] System.gc() triggered by user");
    });

    clearLogButton.addActionListener(e -> {
      logPanel.setText("");
    });

    toolbar.add(startButton);
    toolbar.add(stopButton);
    toolbar.addSeparator();
    toolbar.add(simulateButton);
    toolbar.add(gcButton);
    toolbar.addSeparator();
    toolbar.add(clearLogButton);

    return toolbar;
  }

  private JPanel createTopPanel() {
    JPanel panel = new JBPanel<>(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(5));

    // Chart panel
    JBScrollPane chartScroll = new JBScrollPane(chartPanel);
    chartScroll.setBorder(JBUI.Borders.empty());
    chartScroll.setPreferredSize(new Dimension(800, 300));
    panel.add(chartScroll, BorderLayout.CENTER);

    // Statistics panel (right side)
    JScrollPane statsScroll = new JBScrollPane(statsPanel);
    statsScroll.setBorder(JBUI.Borders.empty());
    statsScroll.setPreferredSize(new Dimension(280, 300));
    panel.add(statsScroll, BorderLayout.EAST);

    return panel;
  }

  private JPanel createBottomPanel() {
    JPanel panel = new JBPanel<>(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(5));

    JLabel logLabel = new JBLabel("Detection Log:");
    logLabel.setBorder(JBUI.Borders.empty(0, 0, 5, 0));
    panel.add(logLabel, BorderLayout.NORTH);

    logPanel.setEditable(false);
    logPanel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
    logPanel.setBackground(UIManager.getColor("Panel.background"));
    logPanel.setBorder(JBUI.Borders.empty(5));

    JBScrollPane logScroll = new JBScrollPane(logPanel);
    logScroll.setPreferredSize(new Dimension(-1, 150));
    panel.add(logScroll, BorderLayout.CENTER);

    return panel;
  }

  private void setupListeners() {
    monitorService.addListener(new MemoryMonitorService.MemoryMonitorListener() {
      @Override
      public void onMemoryUpdate(@NotNull MemoryMonitorService.MemorySnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
          chartPanel.addDataPoint(snapshot);
          statsPanel.updateStats(snapshot);
          updateStatus(snapshot);
        });
      }

      @Override
      public void onGcEvent(@NotNull MemoryMonitorService.GcEvent event) {
        SwingUtilities.invokeLater(() -> {
          String message = String.format("[GC] %s: reclaimed %s in %d ms",
                  event.gcName(),
                  monitorService.formatBytes(event.oldGenReclaimed()),
                  event.duration()
          );
          appendLog(message);
        });
      }

      @Override
      public void onLeakDetected(@NotNull MemoryMonitorService.LeakAlert alert) {
        SwingUtilities.invokeLater(() -> {
          String color = switch (alert.severity()) {
            case INFO -> "#00AA00";
            case WARNING -> "#FF8800";
            case HIGH_RISK, CRITICAL -> "#FF0000";
          };
          String message = String.format("⚠️ [%s] %s", alert.severity(), alert.message());
          appendLog(message, color);

          // Show balloon notification for critical alerts
          if (alert.severity() == MemoryMonitorService.LeakAlert.Severity.CRITICAL) {
            showNotification(alert.message());
          }
        });
      }

      @Override
      public void onRapidGrowthDetected(@NotNull MemoryMonitorService.RapidGrowthAlert alert) {
        SwingUtilities.invokeLater(() -> {
          String message = String.format(
                  "🚨 RAPID GROWTH: %.2f%% growth rate! Old gen: %s (↑%.1f%%)",
                  alert.growthRate(),
                  monitorService.formatBytes(alert.currentOldGen()),
                  alert.oldGenGrowthPercent()
          );
          appendLog(message, "#FF8800");

          // Show GC confirmation dialog
          showGcConfirmationDialog(alert);
        });
      }

      @Override
      public void onMonitoringStateChanged(boolean isMonitoring) {
        SwingUtilities.invokeLater(() -> {
          updateButtonState();
          String status = isMonitoring ? "● Running" : "● Stopped";
          statusLabel.setText(status);
          appendLog(isMonitoring ? "Monitoring started" : "Monitoring stopped");
        });
      }

      @Override
      public void onBaselineEstablished(long baselineHeap, long baselineOldGen) {
        SwingUtilities.invokeLater(() -> {
          appendLog(String.format(
                  "Baseline established - Heap: %s, Old Gen: %s",
                  monitorService.formatBytes(baselineHeap),
                  monitorService.formatBytes(baselineOldGen)
          ));
        });
      }

      @Override
      public void onMemoryThresholdWarning(double heapUsagePercent, double oldGenUsagePercent) {
        SwingUtilities.invokeLater(() -> {
          if (heapUsagePercent > 90) {
            appendLog(String.format(
                    "⚠️ WARNING: Heap usage at %.1f%%! Potential memory pressure.",
                    heapUsagePercent
            ), "#FF8800");
          }
        });
      }
    });
  }

  private void startUIUpdater() {
    uiUpdater = Executors.newSingleThreadScheduledExecutor();
    uiUpdater.scheduleAtFixedRate(() -> {
      if (monitorService.isMonitoring()) {
        MemoryMonitorService.MemorySnapshot snapshot = monitorService.getCurrentSnapshot();
        SwingUtilities.invokeLater(() -> {
          chartPanel.addDataPoint(snapshot);
          statsPanel.updateStats(snapshot);
          updateStatus(snapshot);
        });
      }
    }, 1, 1, TimeUnit.SECONDS);
  }

  private void updateStatus(MemoryMonitorService.MemorySnapshot snapshot) {
    double heapPercent = (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted();
    String status = String.format("● %s | Heap: %s / %s (%.1f%%) | Old Gen: %s",
            monitorService.isMonitoring() ? "Running" : "Stopped",
            monitorService.formatBytes(snapshot.heapUsed()),
            monitorService.formatBytes(snapshot.heapCommitted()),
            heapPercent,
            monitorService.formatBytes(snapshot.oldGenUsed())
    );
    statusLabel.setText(status);
  }

  private void updateButtonState() {
    boolean isRunning = monitorService.isMonitoring();
    startButton.setEnabled(!isRunning);
    stopButton.setEnabled(isRunning);
  }

  private void appendLog(String message) {
    String timestamp = String.format("[%tT] ", System.currentTimeMillis());
    logPanel.append(timestamp + message + "\n");
    logPanel.setCaretPosition(logPanel.getDocument().getLength());

    // Keep only last 500 lines
    if (logPanel.getLineCount() > 500) {
      try {
        int end = logPanel.getLineStartOffset(100);
        logPanel.replaceRange("", 0, end);
      } catch (Exception e) {
        // Ignore
      }
    }
  }

  private void appendLog(String message, String color) {
    String timestamp = String.format("[%tT] ", System.currentTimeMillis());
    String colored = String.format("<span style='color:%s'>%s</span>", color, message);
    // For JTextArea, we can't use HTML easily, so just append plain text
    logPanel.append(timestamp + message + "\n");
    logPanel.setCaretPosition(logPanel.getDocument().getLength());
  }

  private void showGcConfirmationDialog(MemoryMonitorService.RapidGrowthAlert alert) {
    GcConfirmationDialog dialog = new GcConfirmationDialog(
            alert,
            () -> {
              monitorService.forceGc();
              appendLog("System.gc() executed by user confirmation");
            },
            () -> {
              appendLog("GC suggestion ignored by user");
            }
    );
    dialog.show();
  }

  private void showNotification(String message) {
    // Simple notification - can be enhanced with proper balloon notifications
    JOptionPane.showMessageDialog(
            this,
            message,
            "Memory Leak Detector",
            JOptionPane.WARNING_MESSAGE
    );
  }

  public void dispose() {
    if (uiUpdater != null) {
      uiUpdater.shutdown();
    }
    monitorService.stopMonitoring();
  }
}
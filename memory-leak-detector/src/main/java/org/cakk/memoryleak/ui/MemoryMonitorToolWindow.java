// src/main/java/org/cakk/memoryleak/ui/MemoryMonitorToolWindow.java
package org.cakk.memoryleak.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MemoryMonitorToolWindow extends SimpleToolWindowPanel {

  private final Project project;
  private final MemoryMonitorService monitorService;
  private final RemoteMemoryMonitorService remoteService;
  private final MemoryChartPanel chartPanel;
  private final StatisticsPanel statsPanel;
  private final JTextArea logPanel;
  private final JLabel statusLabel;
  private final JLabel remoteStatusLabel;
  private final JButton startButton;
  private final JButton stopButton;
  private final JButton connectButton;
  private final JButton disconnectButton;
  private final JButton simulateButton;
  private final JButton gcButton;
  private final JButton clearLogButton;
  private final JButton analyzeButton;

  private ScheduledExecutorService uiUpdater;
  private ScheduledExecutorService remoteUpdater;
  private boolean isRemoteMode = false;

  public MemoryMonitorToolWindow(@NotNull Project project) {
    super(true, true);
    this.project = project;
    this.monitorService = project.getService(MemoryMonitorService.class);
    this.remoteService = project.getService(RemoteMemoryMonitorService.class);
    this.chartPanel = new MemoryChartPanel();
    this.statsPanel = new StatisticsPanel();
    this.logPanel = new JTextArea();
    this.statusLabel = new JLabel("● Local Monitoring (Stopped)");
    this.remoteStatusLabel = new JLabel("Not Connected");
    this.startButton = new JButton("Start Local");
    this.stopButton = new JButton("Stop");
    this.connectButton = new JButton("Connect to Process");
    this.disconnectButton = new JButton("Disconnect");
    this.simulateButton = new JButton("Simulate Leak");
    this.gcButton = new JButton("Run GC");
    this.clearLogButton = new JButton("Clear Log");
    this.analyzeButton = new JButton("Analyze Leak");

    initUI();
    setupLocalListeners();
    setupRemoteListener();
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
    JPanel statusBar = new JPanel(new BorderLayout());
    statusBar.setBorder(JBUI.Borders.empty(2, 5, 2, 5));

    // Left side - monitoring status
    JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT));
    statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
    leftStatus.add(statusLabel);

    JPanel centerStatus = new JPanel(new FlowLayout(FlowLayout.CENTER));
    remoteStatusLabel.setFont(remoteStatusLabel.getFont().deriveFont(Font.PLAIN, 11f));
    remoteStatusLabel.setForeground(Color.GRAY);
    centerStatus.add(remoteStatusLabel);

    JPanel rightStatus = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    JLabel metricsLabel = new JLabel("GC: --% | Heap: --% | Old: --%");
    metricsLabel.setFont(metricsLabel.getFont().deriveFont(Font.PLAIN, 10f));
    metricsLabel.setForeground(new Color(150, 150, 150));
    rightStatus.add(metricsLabel);

    statusBar.add(leftStatus, BorderLayout.WEST);
    statusBar.add(centerStatus, BorderLayout.CENTER);
    statusBar.add(rightStatus, BorderLayout.EAST);

    add(statusBar, BorderLayout.SOUTH);
  }

  private JToolBar createToolbar() {
    JToolBar toolbar = new JToolBar();
    toolbar.setFloatable(false);

    // Local monitoring buttons
    startButton.setIcon(UIManager.getIcon("AllIcons.Actions.Execute"));
    startButton.addActionListener(e -> {
      if (!isRemoteMode) {
        monitorService.startMonitoring();
        appendLog("Local monitoring started - tracking IDE JVM memory");
        updateButtonState();
      } else {
        appendLog("Cannot start local monitoring while connected to remote process. Disconnect first.");
      }
    });

    stopButton.setIcon(UIManager.getIcon("AllIcons.Actions.Suspend"));
    stopButton.setEnabled(false);
    stopButton.addActionListener(e -> {
      monitorService.stopMonitoring();
      updateButtonState();
      appendLog("Local monitoring stopped");
    });

    // Remote connection buttons
    connectButton.setIcon(UIManager.getIcon("AllIcons.General.Web"));
    connectButton.addActionListener(e -> {
      ProcessSelectionDialog dialog = new ProcessSelectionDialog(project, remoteService);
      dialog.show();
    });

    disconnectButton.setIcon(UIManager.getIcon("AllIcons.Actions.Cancel"));
    disconnectButton.setEnabled(false);
    disconnectButton.addActionListener(e -> {
      remoteService.disconnect();
      isRemoteMode = false;
      remoteStatusLabel.setText("Not Connected");
      remoteStatusLabel.setForeground(Color.GRAY);
      appendLog("Disconnected from remote process");
      updateButtonState();
    });

    // Simulate leak button
    simulateButton.setIcon(UIManager.getIcon("AllIcons.General.Warning"));
    simulateButton.addActionListener(e -> {
      LeakSimulationDialog dialog = new LeakSimulationDialog(project);
      dialog.show();
    });

    // GC button
    gcButton.setIcon(UIManager.getIcon("AllIcons.Actions.GC"));
    gcButton.addActionListener(e -> {
      if (isRemoteMode) {
        remoteService.forceRemoteGc();
        appendLog("[Remote GC] Triggered on process " + remoteService.getConnectedPid());
      } else {
        monitorService.forceGc();
        appendLog("[Local GC] Triggered on IDE JVM");
      }
    });

    // Analyze button
    analyzeButton.setIcon(UIManager.getIcon("AllIcons.General.Information"));
    analyzeButton.addActionListener(e -> {
      showLeakAnalysis();
    });

    // Clear log button
    clearLogButton.addActionListener(e -> {
      logPanel.setText("");
      appendLog("Log cleared");
    });

    toolbar.add(startButton);
    toolbar.add(stopButton);
    toolbar.addSeparator();
    toolbar.add(connectButton);
    toolbar.add(disconnectButton);
    toolbar.addSeparator();
    toolbar.add(simulateButton);
    toolbar.add(gcButton);
    toolbar.add(analyzeButton);
    toolbar.addSeparator();
    toolbar.add(clearLogButton);

    return toolbar;
  }

  private JPanel createTopPanel() {
    JPanel panel = new JBPanel<>(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(5));

    // Chart panel (left/center)
    JBScrollPane chartScroll = new JBScrollPane(chartPanel);
    chartScroll.setBorder(JBUI.Borders.empty());
    chartScroll.setPreferredSize(new Dimension(800, 300));
    panel.add(chartScroll, BorderLayout.CENTER);

    // Statistics panel (right side)
    JScrollPane statsScroll = new JBScrollPane(statsPanel);
    statsScroll.setBorder(JBUI.Borders.empty());
    statsScroll.setPreferredSize(new Dimension(320, 300));
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

  private void setupLocalListeners() {
    monitorService.addListener(new MemoryMonitorService.MemoryMonitorListener() {
      @Override
      public void onMemoryUpdate(@NotNull MemoryMonitorService.MemorySnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
          if (!isRemoteMode) {
            chartPanel.addLocalDataPoint(snapshot);
            statsPanel.updateStats(snapshot);
            updateLocalStatus(snapshot);
          }
        });
      }

      @Override
      public void onGcEvent(@NotNull MemoryMonitorService.GcEvent event) {
        SwingUtilities.invokeLater(() -> {
          if (!isRemoteMode) {
            String message = String.format("[Local GC] %s: reclaimed %s in %d ms",
                    event.gcName(),
                    monitorService.formatBytes(event.oldGenReclaimed()),
                    event.duration()
            );
            appendLog(message);
            statsPanel.addGcEvent(event.duration());

            double efficiency = event.oldGenBefore() > 0 ?
                    (event.oldGenReclaimed() * 100.0) / event.oldGenBefore() : 0;
            statsPanel.updateGcEfficiency(efficiency);
          }
        });
      }

      @Override
      public void onLeakDetected(@NotNull MemoryMonitorService.LeakAlert alert) {
        SwingUtilities.invokeLater(() -> {
          if (!isRemoteMode) {
            String color = switch (alert.severity()) {
              case INFO -> "#00AA00";
              case WARNING -> "#FF8800";
              case HIGH_RISK, CRITICAL -> "#FF0000";
            };
            String message = String.format("⚠️ [%s] %s", alert.severity(), alert.message());
            appendLog(message, color);

            if (alert.severity() == MemoryMonitorService.LeakAlert.Severity.CRITICAL) {
              showNotification(alert.message());
            }
          }
        });
      }

      @Override
      public void onRapidGrowthDetected(@NotNull MemoryMonitorService.RapidGrowthAlert alert) {
        SwingUtilities.invokeLater(() -> {
          if (!isRemoteMode) {
            String message = String.format(
                    "🚨 RAPID GROWTH: %.2f%% growth rate! Old Gen: %s (↑%.1f%%)",
                    alert.growthRate(),
                    monitorService.formatBytes(alert.currentOldGen()),
                    alert.oldGenGrowthPercent()
            );
            appendLog(message, "#FF8800");
            showGcConfirmationDialog(alert);
          }
        });
      }

      @Override
      public void onMonitoringStateChanged(boolean isMonitoring) {
        SwingUtilities.invokeLater(() -> {
          if (!isRemoteMode) {
            updateButtonState();
            statusLabel.setText(isMonitoring ? "● Local Monitoring (Running)" : "● Local Monitoring (Stopped)");
            appendLog(isMonitoring ? "Local monitoring started" : "Local monitoring stopped");
          }
        });
      }

      @Override
      public void onBaselineEstablished(long baselineHeap, long baselineOldGen) {
        SwingUtilities.invokeLater(() -> {
          if (!isRemoteMode) {
            appendLog(String.format(
                    "Baseline established - Heap: %s, Old Gen: %s",
                    monitorService.formatBytes(baselineHeap),
                    monitorService.formatBytes(baselineOldGen)
            ));
          }
        });
      }

      @Override
      public void onMetricsUpdate(double heapGrowthPercent, double oldGenGrowthPercent,
                                  double recentGrowthRate, double gcEfficiency) {
        SwingUtilities.invokeLater(() -> {
          if (!isRemoteMode) {
            statsPanel.updateHeapGrowth(heapGrowthPercent);
            statsPanel.updateOldGenGrowth(oldGenGrowthPercent);
            statsPanel.updateRecentGrowthRate(recentGrowthRate);
            statsPanel.updateGcEfficiency(gcEfficiency);
          }
        });
      }
    });
  }

  private void setupRemoteListener() {
    remoteService.addListener(new RemoteMemoryMonitorService.RemoteMemoryListener() {
      @Override
      public void onMemoryUpdate(RemoteMemoryMonitorService.RemoteMemorySnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
          if (isRemoteMode) {
            chartPanel.addRemoteDataPoint(snapshot);
            statsPanel.updateRemoteStats(snapshot);
            updateRemoteStatus(snapshot);
          }
        });
      }

      @Override
      public void onGcEvent(RemoteMemoryMonitorService.GcEvent event) {
        SwingUtilities.invokeLater(() -> {
          if (isRemoteMode) {
            String message = String.format("[Remote GC] %s: reclaimed %s in %d ms",
                    event.gcName(),
                    remoteService.formatBytes(event.oldGenReclaimed()),
                    event.duration()
            );
            appendLog(message);
            statsPanel.addGcEvent(event.duration());

            double efficiency = event.oldGenBefore() > 0 ?
                    (event.oldGenReclaimed() * 100.0) / event.oldGenBefore() : 0;
            statsPanel.updateGcEfficiency(efficiency);
          }
        });
      }

      @Override
      public void onConnectionStatusChanged(boolean connected, String pid) {
        SwingUtilities.invokeLater(() -> {
          isRemoteMode = connected;
          if (connected) {
            remoteStatusLabel.setText("● Connected to: " + pid);
            remoteStatusLabel.setForeground(new Color(80, 255, 80));
            appendLog("Connected to remote process: " + pid);
            appendLog("Now monitoring application memory, not IDE memory");
            startRemotePolling();
            updateButtonState();
          } else {
            remoteStatusLabel.setText("Not Connected");
            remoteStatusLabel.setForeground(Color.GRAY);
            stopRemotePolling();
            updateButtonState();
          }
        });
      }

      @Override
      public void onError(String error) {
        SwingUtilities.invokeLater(() -> {
          appendLog("Remote error: " + error);
        });
      }
    });
  }

  private void startRemotePolling() {
    if (remoteUpdater != null) {
      remoteUpdater.shutdown();
    }
    remoteUpdater = Executors.newSingleThreadScheduledExecutor();
    remoteUpdater.scheduleAtFixedRate(() -> {
      if (isRemoteMode && remoteService.isConnected()) {
        RemoteMemoryMonitorService.RemoteMemorySnapshot snapshot =
                remoteService.getCurrentSnapshot();
        if (snapshot != null) {
          SwingUtilities.invokeLater(() -> {
            chartPanel.addRemoteDataPoint(snapshot);
            statsPanel.updateRemoteStats(snapshot);
          });
        }
      }
    }, 1, 1, TimeUnit.SECONDS);
  }

  private void stopRemotePolling() {
    if (remoteUpdater != null) {
      remoteUpdater.shutdown();
      remoteUpdater = null;
    }
  }

  private void startUIUpdater() {
    uiUpdater = Executors.newSingleThreadScheduledExecutor();
    uiUpdater.scheduleAtFixedRate(() -> {
      if (monitorService.isMonitoring() && !isRemoteMode) {
        MemoryMonitorService.MemorySnapshot snapshot = monitorService.getCurrentSnapshot();
        SwingUtilities.invokeLater(() -> {
          chartPanel.addLocalDataPoint(snapshot);
          statsPanel.updateStats(snapshot);
          updateLocalStatus(snapshot);
        });
      }
    }, 1, 1, TimeUnit.SECONDS);
  }

  private void updateLocalStatus(MemoryMonitorService.MemorySnapshot snapshot) {
    double heapPercent = (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted();
    String status = String.format("Heap: %s / %s (%.1f%%) | Old Gen: %s | Young: %s",
            monitorService.formatBytes(snapshot.heapUsed()),
            monitorService.formatBytes(snapshot.heapCommitted()),
            heapPercent,
            monitorService.formatBytes(snapshot.oldGenUsed()),
            monitorService.formatBytes(snapshot.youngGenUsed())
    );
    statusLabel.setText("● " + status);
  }

  private void updateRemoteStatus(RemoteMemoryMonitorService.RemoteMemorySnapshot snapshot) {
    double heapPercent = (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted();
    String status = String.format("Heap: %s / %s (%.1f%%) | Old Gen: %s | Young: %s",
            remoteService.formatBytes(snapshot.heapUsed()),
            remoteService.formatBytes(snapshot.heapCommitted()),
            heapPercent,
            remoteService.formatBytes(snapshot.oldGenUsed()),
            remoteService.formatBytes(snapshot.youngGenUsed())
    );
    statusLabel.setText("● " + status);
  }

  private void updateButtonState() {
    boolean isLocalRunning = monitorService.isMonitoring();
    boolean isRemoteConnected = isRemoteMode;

    startButton.setEnabled(!isLocalRunning && !isRemoteConnected);
    stopButton.setEnabled(isLocalRunning);
    connectButton.setEnabled(!isRemoteConnected);
    disconnectButton.setEnabled(isRemoteConnected);
    simulateButton.setEnabled(true);
    gcButton.setEnabled(true);
    analyzeButton.setEnabled(true);
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

  private void showLeakAnalysis() {
    try {
      String analysis;
      if (isRemoteMode) {
        analysis = "Remote Process Analysis:\n\n";
        analysis += "To analyze remote process memory, please use the local monitoring mode.\n";
        analysis += "Disconnect from remote to analyze local memory patterns.";
      } else {
        analysis = monitorService.getFormattedLeakAnalysis();
      }

      JTextArea textArea = new JTextArea(analysis);
      textArea.setEditable(false);
      textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      textArea.setBackground(UIManager.getColor("Panel.background"));
      textArea.setBorder(JBUI.Borders.empty(10));

      JScrollPane scrollPane = new JScrollPane(textArea);
      scrollPane.setPreferredSize(new Dimension(650, 450));

      JOptionPane.showMessageDialog(
              this,
              scrollPane,
              "Memory Leak Analysis Report",
              JOptionPane.INFORMATION_MESSAGE
      );

      appendLog("Leak analysis report displayed");
    } catch (Exception e) {
      appendLog("Error generating leak analysis: " + e.getMessage());
    }
  }

  private void showNotification(String message) {
    JOptionPane.showMessageDialog(
            this,
            message,
            "Memory Leak Detector Alert",
            JOptionPane.WARNING_MESSAGE
    );
  }

  private void appendLog(String message) {
    String timestamp = String.format("[%tT] ", System.currentTimeMillis());
    logPanel.append(timestamp + message + "\n");
    logPanel.setCaretPosition(logPanel.getDocument().getLength());

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
    // For JTextArea, we can't use HTML easily, so just append with indicator
    String indicator = switch (color) {
      case "#FF0000" -> "🔴 ";
      case "#FF8800" -> "🟠 ";
      case "#00AA00" -> "🟢 ";
      default -> "   ";
    };
    appendLog(indicator + message);
  }

  public void dispose() {
    if (uiUpdater != null) {
      uiUpdater.shutdown();
    }
    if (remoteUpdater != null) {
      remoteUpdater.shutdown();
    }
    monitorService.stopMonitoring();
    remoteService.disconnect();
  }

  private void updateConnectionStatus() {
    if (isRemoteMode && remoteService.isConnected()) {
      remoteStatusLabel.setText("● Connected to: " + remoteService.getConnectedPid());
      remoteStatusLabel.setForeground(new Color(80, 255, 80));
      statusLabel.setText("● Remote Monitoring (Application)");
    } else {
      remoteStatusLabel.setText("Not Connected");
      remoteStatusLabel.setForeground(Color.GRAY);
      statusLabel.setText("● Local Monitoring (IDE)");
    }
  }
}
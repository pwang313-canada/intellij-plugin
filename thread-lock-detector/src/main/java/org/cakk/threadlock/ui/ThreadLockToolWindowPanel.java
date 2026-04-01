package org.cakk.threadlock.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.cakk.threadlock.models.ThreadLockIssue;
import org.cakk.threadlock.services.RemoteDeadlockMonitorService;

import javax.swing.table.DefaultTableCellRenderer;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ThreadLockToolWindowPanel {
  private final JPanel mainPanel;
  private final JTable issuesTable;
  private final IssuesTableModel tableModel;
  private final JLabel statusLabel;
  private final JButton clearButton;

  // Monitor UI components
  private final JButton startStopButton;
  private final JButton connectButton;
  private final JButton disconnectButton;
  private final JButton refreshButton;
  private final JComboBox<String> processCombo;
  private final JLabel monitorStatusLabel;
  private JBTextArea deadlockDetailsArea;

  private final Project project;
  private final RemoteDeadlockMonitorService remoteService;

  public ThreadLockToolWindowPanel(Project project) {
    this.project = project;
    this.remoteService = project.getService(RemoteDeadlockMonitorService.class);

    mainPanel = new JPanel(new BorderLayout());

    // ---- Top panel for static analysis results (no "Analysis Results:" label) ----
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    clearButton = new JButton("Clear");
    clearButton.addActionListener(e -> clearResults());
    topPanel.add(clearButton);
    statusLabel = new JLabel("Ready");
    topPanel.add(statusLabel);
    mainPanel.add(topPanel, BorderLayout.NORTH);

    // ---- Static results table ----
    tableModel = new IssuesTableModel();
    issuesTable = new JTable(tableModel);
    // Set row height to accommodate icons
    issuesTable.setRowHeight(18);

// Custom renderer for severity column (column 0)
    issuesTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
      @Override
      public Component getTableCellRendererComponent(JTable table, Object value,
                                                     boolean isSelected, boolean hasFocus,
                                                     int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        if (c instanceof JLabel) {
          JLabel label = (JLabel) c;
          String severity = (String) value;
          if ("ERROR".equals(severity)) {
            label.setIcon(AllIcons.General.Error);
          } else if ("WARNING".equals(severity)) {
            label.setIcon(AllIcons.General.Warning);
          } else {
            label.setIcon(AllIcons.General.Information);
          }
          label.setHorizontalTextPosition(SwingConstants.RIGHT);
        }
        return c;
      }
    });

    JBScrollPane tableScroll = new JBScrollPane(issuesTable);

    // ---- Monitor area: will be placed in a split pane (top = static table, bottom = monitor) ----
    // ---- Create components for monitor area ----
    // Connection controls (collapsible)
    JPanel connectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    connectPanel.add(new JLabel("Process:"));
    processCombo = new JComboBox<>();
    refreshProcessList();
    connectPanel.add(processCombo);

    refreshButton = new JButton("Refresh");
    refreshButton.addActionListener(e -> refreshProcessList());
    connectPanel.add(refreshButton);

    connectButton = new JButton("Connect");
    connectButton.addActionListener(e -> connectToProcess());
    connectPanel.add(connectButton);

    disconnectButton = new JButton("Disconnect");
    disconnectButton.setEnabled(false);
    disconnectButton.addActionListener(e -> disconnect());
    connectPanel.add(disconnectButton);

    // Collapsible wrapper for connection panel
    JPanel connectWrapper = new JPanel(new BorderLayout());
    JToggleButton toggleConnectButton = new JToggleButton("▼ Connection", true);
    toggleConnectButton.addActionListener(e -> {
      boolean visible = toggleConnectButton.isSelected();
      connectPanel.setVisible(visible);
      toggleConnectButton.setText(visible ? "▼ Connection" : "▶ Connection");
    });
    connectWrapper.add(toggleConnectButton, BorderLayout.NORTH);
    connectWrapper.add(connectPanel, BorderLayout.CENTER);

    // Monitor controls (start/stop, clear log, status)
    JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    startStopButton = new JButton("Start Monitoring");
    startStopButton.setEnabled(false);
    startStopButton.addActionListener(e -> toggleMonitoring());
    controlPanel.add(startStopButton);

    JButton clearLogButton = new JButton("Clear Log");
    clearLogButton.addActionListener(e -> deadlockDetailsArea.setText(""));
    controlPanel.add(clearLogButton);

    monitorStatusLabel = new JLabel("Not connected");
    controlPanel.add(monitorStatusLabel);

    // Combine connection wrapper and control panel in a vertical box
    JPanel monitorTop = new JPanel();
    monitorTop.setLayout(new BoxLayout(monitorTop, BoxLayout.Y_AXIS));
    monitorTop.add(connectWrapper);
    monitorTop.add(controlPanel);

    // Deadlock details area (log)
    deadlockDetailsArea = new JBTextArea();
    deadlockDetailsArea.setEditable(false);
    deadlockDetailsArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
    deadlockDetailsArea.setRows(5);
    JBScrollPane detailsScroll = new JBScrollPane(deadlockDetailsArea);
    detailsScroll.setBorder(BorderFactory.createTitledBorder("Deadlock Log"));

    // ---- Create a split pane inside the monitor area (top = controls, bottom = log) ----
    JSplitPane monitorSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, monitorTop, detailsScroll);
    monitorSplit.setResizeWeight(0.3);   // 30% for controls, 70% for log initially
    monitorSplit.setContinuousLayout(true);
    monitorSplit.setOneTouchExpandable(true); // adds collapse/expand arrows

    // ---- Split between static table and monitor area ----
    JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, monitorSplit);
    mainSplit.setResizeWeight(0.7);          // 70% top (table), 30% bottom (monitor)
    mainSplit.setContinuousLayout(true);
    mainSplit.setOneTouchExpandable(true);
    mainPanel.add(mainSplit, BorderLayout.CENTER);

    // ---- Double‑click navigation for static issues ----
    issuesTable.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          int row = issuesTable.getSelectedRow();
          if (row != -1) {
            ThreadLockIssue issue = tableModel.getIssueAt(row);
            navigateToIssue(issue);
          }
        }
      }
    });
  }

  private void refreshProcessList() {
    processCombo.removeAllItems();
    for (String pidInfo : RemoteDeadlockMonitorService.listRunningJavaProcesses()) {
      processCombo.addItem(pidInfo);
    }
  }

  private void connectToProcess() {
    String selected = (String) processCombo.getSelectedItem();
    if (selected == null) return;
    String pid = selected.split(" - ")[0];
    boolean success = remoteService.connect(pid);
    if (success) {
      monitorStatusLabel.setText("Connected to PID " + pid);
      connectButton.setEnabled(false);
      disconnectButton.setEnabled(true);
      startStopButton.setEnabled(true);
    } else {
      monitorStatusLabel.setText("Connection failed");
    }
  }

  private void disconnect() {
    remoteService.disconnect();
    monitorStatusLabel.setText("Disconnected");
    connectButton.setEnabled(true);
    disconnectButton.setEnabled(false);
    startStopButton.setEnabled(false);
    startStopButton.setText("Start Monitoring");
  }

  private void toggleMonitoring() {
    if (remoteService.isMonitoring()) {
      remoteService.stopMonitoring();
      startStopButton.setText("Start Monitoring");
      monitorStatusLabel.setText("Monitoring stopped");
    } else {
      remoteService.startMonitoring();
      startStopButton.setText("Stop Monitoring");
      monitorStatusLabel.setText("Monitoring active");
    }
  }

  public JComponent getContent() {
    return mainPanel;
  }

  public void clearResults() {
    tableModel.clear();
    statusLabel.setText("Cleared");
  }

  public void setStatus(String status) {
    statusLabel.setText(status);
  }

  public void updateResults(List<ThreadLockIssue> issues) {
    tableModel.setIssues(issues);
    statusLabel.setText("Analysis complete. Found " + issues.size() + " issues.");
  }

  public void addDeadlockInfo(String details) {
    ApplicationManager.getApplication().invokeLater(() -> {
      deadlockDetailsArea.append("--- Deadlock at " + new Date() + " ---\n");
      deadlockDetailsArea.append(details);
      deadlockDetailsArea.append("\n\n");
      deadlockDetailsArea.setCaretPosition(deadlockDetailsArea.getDocument().getLength());
    });
  }

  public void setMonitorStatus(String status) {
    ApplicationManager.getApplication().invokeLater(() -> monitorStatusLabel.setText(status));
  }

  private void navigateToIssue(ThreadLockIssue issue) {
    if (issue == null) return;
    if (issue.getFile() == null) return;
    int lineNumber = issue.getLineNumber();
    if (lineNumber <= 0) return;

    VirtualFile virtualFile = issue.getFile().getVirtualFile();
    if (virtualFile == null) return;

    OpenFileDescriptor descriptor = new OpenFileDescriptor(project, virtualFile, lineNumber - 1, 0);
    FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
  }

  // ---- Inner table model ----
  private static class IssuesTableModel extends AbstractTableModel {
    private final String[] COLUMNS = {"Severity", "Type", "Description", "File", "Line"};
    private List<ThreadLockIssue> issues = new ArrayList<>();

    void setIssues(List<ThreadLockIssue> issues) {
      this.issues = new ArrayList<>(issues);
      fireTableDataChanged();
    }

    void clear() {
      issues.clear();
      fireTableDataChanged();
    }

    ThreadLockIssue getIssueAt(int row) {
      return issues.get(row);
    }

    @Override
    public int getRowCount() {
      return issues.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      ThreadLockIssue issue = issues.get(rowIndex);
      return switch (columnIndex) {
        case 0 -> issue.getSeverity().toString();
        case 1 -> issue.getType();
        case 2 -> issue.getDescription();
        case 3 -> issue.getFile() != null ? issue.getFile().getName() : "";
        case 4 -> issue.getLineNumber();
        default -> null;
      };
    }

    @Override
    public String getColumnName(int column) {
      return COLUMNS[column];
    }
  }
}
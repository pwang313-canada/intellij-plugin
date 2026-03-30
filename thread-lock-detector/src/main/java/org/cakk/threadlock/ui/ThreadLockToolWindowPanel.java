package org.cakk.threadlock.ui;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.cakk.threadlock.models.ThreadLockIssue;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class ThreadLockToolWindowPanel {

  private final Project project;

  private JPanel mainPanel;
  private JLabel statusLabel;
  private DefaultListModel<ThreadLockIssue> listModel;
  private JList<ThreadLockIssue> resultList;


  public ThreadLockToolWindowPanel(Project project) {
    this.project = project;

    mainPanel = new JPanel(new BorderLayout());

    // -----------------------------
    // Status Bar
    // -----------------------------
    statusLabel = new JLabel("Ready");
    statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
    mainPanel.add(statusLabel, BorderLayout.NORTH);

    // -----------------------------
    // Result List
    // -----------------------------
    listModel = new DefaultListModel<>();
    resultList = new JList<>(listModel);

    // Custom renderer (format display)
    resultList.setCellRenderer(new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(
              JList<?> list,
              Object value,
              int index,
              boolean isSelected,
              boolean cellHasFocus) {

        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (value instanceof ThreadLockIssue) {
          setText(formatIssue((ThreadLockIssue) value));
        }

        return this;
      }
    });

    resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    // -----------------------------
    // Double-click navigation
    // -----------------------------
    resultList.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          ThreadLockIssue issue = resultList.getSelectedValue();
          if (issue != null) {
            navigateToIssue(issue);
          }
        }
      }
    });

    JScrollPane scrollPane = new JScrollPane(resultList);
    mainPanel.add(scrollPane, BorderLayout.CENTER);
  }

  public JPanel getContent() {
    return mainPanel;
  }

  // -----------------------------
  // UI Update Methods
  // -----------------------------

  public void setStatus(String status) {
    SwingUtilities.invokeLater(() -> statusLabel.setText(status));
  }

  public void clearResults() {
    SwingUtilities.invokeLater(() -> listModel.clear());
  }

  public void updateResults(List<ThreadLockIssue> issues) {
    SwingUtilities.invokeLater(() -> {
      listModel.clear();

      if (issues == null || issues.isEmpty()) {
        statusLabel.setText("No thread lock issues found ✅");
        return;
      }

      for (ThreadLockIssue issue : issues) {
        listModel.addElement(issue);
      }

      statusLabel.setText("Found " + issues.size() + " issue(s)");
    });
  }

  // -----------------------------
  // Navigation
  // -----------------------------

  private void navigateToIssue(ThreadLockIssue issue) {
    if (issue.getFile() == null) return;

    VirtualFile virtualFile = issue.getFile().getVirtualFile();
    if (virtualFile == null) return;

    int line = Math.max(issue.getLineNumber() - 1, 0); // IntelliJ is 0-based

    OpenFileDescriptor descriptor =
            new OpenFileDescriptor(project, virtualFile, line, 0);

    descriptor.navigate(true);
  }

  // -----------------------------
  // Formatting Helpers
  // -----------------------------

  private String formatIssue(ThreadLockIssue issue) {
    String severity = formatSeverity(issue.getSeverity());
    String fileName = issue.getFile() != null
            ? issue.getFile().getName()
            : "UnknownFile";

    int line = issue.getLineNumber();

    return String.format(
            "[%s] %s:%d - %s",
            severity,
            fileName,
            line,
            issue.getMessage()
    );
  }

  private String formatSeverity(ThreadLockIssue.Severity severity) {
    if (severity == null) return "INFO";

    switch (severity) {
      case ERROR:
        return "ERROR 🔴";
      case WARNING:
        return "WARNING 🟠";
      case WEAK_WARNING:
        return "WEAK ⚪";
      case INFO:
      default:
        return "INFO 🔵";
    }
  }
}
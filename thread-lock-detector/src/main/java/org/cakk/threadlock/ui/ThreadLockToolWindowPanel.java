package org.cakk.threadlock.ui;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.cakk.threadlock.models.ThreadLockIssue;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ThreadLockToolWindowPanel {
  private final JPanel mainPanel;
  private final IssuesTableModel tableModel;
  private final JTable issuesTable;
  private final JLabel statusLabel;
  private final JButton clearButton;

  public ThreadLockToolWindowPanel(Project project) {
    mainPanel = new JPanel(new BorderLayout());

    // Top panel: label, button, status
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    topPanel.add(new JLabel("Results:"));
    clearButton = new JButton("Clear");
    clearButton.addActionListener(e -> clearResults());
    topPanel.add(clearButton);

    statusLabel = new JLabel("Ready");
    topPanel.add(statusLabel);

    mainPanel.add(topPanel, BorderLayout.NORTH);

    // Table for results
    tableModel = new IssuesTableModel();
    issuesTable = new JBTable(tableModel);
    JBScrollPane scrollPane = new JBScrollPane(issuesTable);
    mainPanel.add(scrollPane, BorderLayout.CENTER);
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

  // Inner table model
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
        case 0 -> issue.getSeverity();
        case 1 -> issue.getType();
        case 2 -> issue.getDescription();
        case 3 -> issue.getFile().getName();
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
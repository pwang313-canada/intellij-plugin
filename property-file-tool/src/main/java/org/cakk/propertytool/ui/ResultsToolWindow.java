package org.cakk.propertytool.ui;

import org.cakk.propertytool.analysis.AnalysisResult;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class ResultsToolWindow {

  private static final Logger LOG = Logger.getInstance(ResultsToolWindow.class);

  private final Project project;
  private final JPanel panel;
  private final JBTable table;
  private final ResultsTableModel tableModel;
  private List<AnalysisResult> results = new ArrayList<>();

  public ResultsToolWindow(Project project) {
    this.project = project;

    this.tableModel = new ResultsTableModel();
    this.table = new JBTable(tableModel);
    this.table.setRowSorter(new TableRowSorter<>(tableModel));
    this.table.setIntercellSpacing(JBUI.emptySize());
    this.table.setShowGrid(false);
    this.table.setAutoCreateRowSorter(true);

    // Double-click navigation
    table.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          int row = table.getSelectedRow();
          if (row != -1) {
            int modelRow = table.convertRowIndexToModel(row);
            AnalysisResult result = tableModel.getResultAt(modelRow);
            navigateToResult(result);
          }
        }
      }
    });

    // Clear button
    JButton clearButton = new JButton("Clear");
    clearButton.addActionListener(e -> clearResults());

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    buttonPanel.add(clearButton);

    this.panel = new JPanel(new BorderLayout());
    this.panel.add(buttonPanel, BorderLayout.NORTH);
    this.panel.add(new JBScrollPane(table), BorderLayout.CENTER);
  }

  public JComponent getComponent() {
    return panel;
  }

  public void setResults(@NotNull List<AnalysisResult> results) {
    this.results = results;
    tableModel.fireTableDataChanged();
  }

  public void show() {
    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
    ToolWindow toolWindow = toolWindowManager.getToolWindow("Properties Validator");
    if (toolWindow == null) {
      // try to create it (requires the factory to be registered)
      toolWindow = toolWindowManager.getToolWindow("Properties Validator");
    }
    if (toolWindow == null) {
      // show an error notification (optional)
      NotificationGroupManager.getInstance()
              .getNotificationGroup("Properties Validator")
              .createNotification("Tool window not found. Check plugin.xml.", NotificationType.ERROR)
              .notify(project);
      return;
    }
    toolWindow.show();
  }
  private void clearResults() {
    this.results = new ArrayList<>();
    tableModel.fireTableDataChanged();
  }

  private void navigateToResult(AnalysisResult result) {
    VirtualFile file = result.getFile();
    if (file == null) return;

    FileEditorManager fem = FileEditorManager.getInstance(project);
    fem.openFile(file, true);

    int lineNumber = result.getLineNumber();
    if (lineNumber > 0) {
      Editor editor = fem.getSelectedTextEditor();
      if (editor != null && file.equals(editor.getVirtualFile())) {
        moveToLine(editor, lineNumber);
      } else {
        FileEditor[] editors = fem.getEditors(file);
        for (FileEditor fe : editors) {
          if (fe instanceof Editor) {
            moveToLine((Editor) fe, lineNumber);
            break;
          }
        }
      }
    }
  }

  private void moveToLine(Editor editor, int lineNumber) {
    LogicalPosition logicalPosition = new LogicalPosition(lineNumber - 1, 0);
    editor.getCaretModel().moveToLogicalPosition(logicalPosition);
    editor.getScrollingModel().scrollTo(logicalPosition, ScrollType.CENTER);
  }

  private class ResultsTableModel extends AbstractTableModel {
    private final String[] columns = {"Severity", "File", "Line", "Message"};

    @Override
    public int getRowCount() {
      return results.size();
    }

    @Override
    public int getColumnCount() {
      return columns.length;
    }

    @Override
    public String getColumnName(int column) {
      return columns[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      AnalysisResult r = results.get(rowIndex);
      switch (columnIndex) {
        case 0:
          return r.getSeverity();
        case 1:
          return r.getFile().getName();
        case 2:
          return r.getLineNumber() > 0 ? r.getLineNumber() : "N/A";
        case 3:
          return r.getMessage();
        default:
          return "";
      }
    }

    public AnalysisResult getResultAt(int row) {
      return results.get(row);
    }
  }
}
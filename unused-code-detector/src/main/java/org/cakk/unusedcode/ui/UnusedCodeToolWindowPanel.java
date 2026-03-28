// src/main/java/org/cakk/unusedcode/ui/UnusedCodeToolWindowPanel.java
package org.cakk.unusedcode.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import org.cakk.unusedcode.models.DuplicateImport;
import org.cakk.unusedcode.models.UnusedClass;
import org.cakk.unusedcode.models.UnusedImport;
import org.cakk.unusedcode.models.UnusedMethod;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Main UI panel for the Unused Code Detector tool window
 */
public class UnusedCodeToolWindowPanel {
  private final Project project;
  private final JPanel panel;
  private Tree resultTree;
  private DefaultMutableTreeNode rootNode;
  private JLabel statusLabel;
  private JButton refreshButton;
  private JButton clearButton;

  public Project getProject() {
    return project;
  }

  public UnusedCodeToolWindowPanel(Project project) {
    this.project = project;
    this.panel = createPanel();
  }

  private JPanel createPanel() {
    JPanel mainPanel = new JPanel(new BorderLayout());

    // Create toolbar
    JToolBar toolBar = new JToolBar();
    toolBar.setFloatable(false);

    refreshButton = new JButton("Refresh", AllIcons.Actions.Refresh);
    refreshButton.addActionListener(e -> refreshAnalysis());
    toolBar.add(refreshButton);

    clearButton = new JButton("Clear", AllIcons.Actions.GC);
    clearButton.addActionListener(e -> clearResults());
    toolBar.add(clearButton);

    JButton exportButton = new JButton("Export", AllIcons.Actions.More);
    exportButton.addActionListener(e -> exportResults());
    toolBar.add(exportButton);

    toolBar.addSeparator();

    JButton expandButton = new JButton("Expand All", AllIcons.Actions.Expandall);
    expandButton.addActionListener(e -> expandAll());
    toolBar.add(expandButton);

    JButton collapseButton = new JButton("Collapse All", AllIcons.Actions.Collapseall);
    collapseButton.addActionListener(e -> collapseAll());
    toolBar.add(collapseButton);

    toolBar.add(Box.createHorizontalGlue());

    // Create tree
    rootNode = new DefaultMutableTreeNode("Unused Code");
    resultTree = new Tree(rootNode);
    resultTree.setCellRenderer(new UnusedCodeTreeRenderer());
    resultTree.setRootVisible(true);
    resultTree.setShowsRootHandles(true);

    // Add double-click navigation
    resultTree.addMouseListener(new java.awt.event.MouseAdapter() {
      @Override
      public void mouseClicked(java.awt.event.MouseEvent e) {
        if (e.getClickCount() == 2) {
          navigateToSelected();
        }
      }

      @Override
      public void mousePressed(java.awt.event.MouseEvent e) {
        if (e.isPopupTrigger()) {
          showPopupMenu(e.getX(), e.getY());
        }
      }

      @Override
      public void mouseReleased(java.awt.event.MouseEvent e) {
        if (e.isPopupTrigger()) {
          showPopupMenu(e.getX(), e.getY());
        }
      }
    });

    // Status label
    statusLabel = new JLabel("Ready");
    statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

    mainPanel.add(toolBar, BorderLayout.NORTH);
    mainPanel.add(new JBScrollPane(resultTree), BorderLayout.CENTER);
    mainPanel.add(statusLabel, BorderLayout.SOUTH);

    return mainPanel;
  }

  public JPanel getPanel() {
    return panel;
  }

  /**
   * Clear all results from the tree
   */
  public void clearResults() {
    SwingUtilities.invokeLater(() -> {
      rootNode.removeAllChildren();
      ((DefaultTreeModel) resultTree.getModel()).reload();
      statusLabel.setText("Ready");
    });
  }

  /**
   * Set the status message
   */
  public void setStatus(String status) {
    SwingUtilities.invokeLater(() -> statusLabel.setText(status));
  }

  /**
   * Repaint the panel
   */
  public void repaint() {
    panel.repaint();
  }

  private void expandAll() {
    for (int i = 0; i < resultTree.getRowCount(); i++) {
      resultTree.expandRow(i);
    }
  }

  private void collapseAll() {
    for (int i = resultTree.getRowCount() - 1; i >= 0; i--) {
      resultTree.collapseRow(i);
    }
    resultTree.expandRow(0);
  }

  private void navigateToSelected() {
    TreePath selectedPath = resultTree.getSelectionPath();
    if (selectedPath == null) return;

    Object lastComponent = selectedPath.getLastPathComponent();
    if (!(lastComponent instanceof DefaultMutableTreeNode)) return;

    DefaultMutableTreeNode node = (DefaultMutableTreeNode) lastComponent;
    Object userObject = node.getUserObject();

    VirtualFile virtualFile = null;
    int lineNumber = -1;

    if (userObject instanceof UnusedImport) {
      UnusedImport unusedImport = (UnusedImport) userObject;
      virtualFile = unusedImport.getContainingFile().getVirtualFile();
      lineNumber = unusedImport.getLineNumber();
    } else if (userObject instanceof UnusedMethod) {
      UnusedMethod method = (UnusedMethod) userObject;
      virtualFile = method.getContainingFile().getVirtualFile();
    } else if (userObject instanceof UnusedClass) {
      UnusedClass unusedClass = (UnusedClass) userObject;
      virtualFile = unusedClass.getContainingFile().getVirtualFile();
    }

    if (virtualFile != null && virtualFile.isValid()) {
      FileEditorManager.getInstance(project).openFile(virtualFile, true);

      if (lineNumber >= 0) {
        final int finalLineNumber = lineNumber;
        ApplicationManager.getApplication().invokeLater(() -> {
          Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
          if (editor != null) {
            int lineStartOffset = editor.getDocument().getLineStartOffset(finalLineNumber);
            editor.getCaretModel().moveToOffset(lineStartOffset);
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
          }
        });
      }
    }
  }

  private void showPopupMenu(int x, int y) {
    TreePath selectedPath = resultTree.getPathForLocation(x, y);
    if (selectedPath == null) return;

    resultTree.setSelectionPath(selectedPath);

    JPopupMenu popup = new JPopupMenu();

    JMenuItem navigateItem = new JMenuItem("Navigate to Code", AllIcons.Actions.Show);
    navigateItem.addActionListener(e -> navigateToSelected());
    popup.add(navigateItem);

    popup.addSeparator();

    JMenuItem removeItem = new JMenuItem("Remove", AllIcons.Actions.DeleteTag);
    removeItem.addActionListener(e -> removeSelected());
    popup.add(removeItem);

    JMenuItem copyItem = new JMenuItem("Copy to Clipboard", AllIcons.Actions.Copy);
    copyItem.addActionListener(e -> copyToClipboard());
    popup.add(copyItem);

    popup.show(resultTree, x, y);
  }

  private void removeSelected() {
    TreePath selectedPath = resultTree.getSelectionPath();
    if (selectedPath == null) return;

    Object lastComponent = selectedPath.getLastPathComponent();
    if (!(lastComponent instanceof DefaultMutableTreeNode)) return;

    DefaultMutableTreeNode node = (DefaultMutableTreeNode) lastComponent;
    Object userObject = node.getUserObject();

    // Get description for confirmation
    String description = getItemDescription(userObject);

    int result = JOptionPane.showConfirmDialog(
            panel,
            "Are you sure you want to remove this unused code?\n\n" + description,
            "Confirm Removal",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
    );

    if (result == JOptionPane.YES_OPTION) {
      try {
        // Remove from PSI
        if (userObject instanceof UnusedImport) {
          removeImport((UnusedImport) userObject);
        } else if (userObject instanceof UnusedMethod) {
          removeMethod((UnusedMethod) userObject);
        } else if (userObject instanceof UnusedClass) {
          removeClass((UnusedClass) userObject);
        }

        // Remove from tree
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
        parent.remove(node);

        // Remove category if empty
        if (parent.getChildCount() == 0) {
          DefaultMutableTreeNode grandParent = (DefaultMutableTreeNode) parent.getParent();
          if (grandParent != null) {
            grandParent.remove(parent);
          }
        }

        ((DefaultTreeModel) resultTree.getModel()).reload();
        setStatus("Removed selected item");

      } catch (Exception e) {
        setStatus("Error removing item: " + e.getMessage());
        e.printStackTrace();
      }
    }
  }

  private String getItemDescription(Object userObject) {
    if (userObject instanceof UnusedImport) {
      UnusedImport imp = (UnusedImport) userObject;
      return String.format("Import: %s\nFile: %s\nLine: %d",
              imp.getImportText(),
              imp.getFilePath(),
              imp.getLineNumber() + 1);
    } else if (userObject instanceof UnusedMethod) {
      UnusedMethod method = (UnusedMethod) userObject;
      return String.format("Method: %s\nFile: %s",
              method.getFullName(),
              method.getFilePath());
    } else if (userObject instanceof UnusedClass) {
      UnusedClass cls = (UnusedClass) userObject;
      return String.format("Class: %s\nFile: %s",
              cls.getFullName(),
              cls.getFilePath());
    }
    return "Unknown item";
  }

  private void removeImport(UnusedImport unusedImport) {
    PsiImportStatement importStmt = unusedImport.getImportStatement();
    if (importStmt == null) return;

    ApplicationManager.getApplication().runWriteAction(() -> {
      try {
        importStmt.delete();
      } catch (Exception e) {
        throw new RuntimeException("Failed to remove import", e);
      }
    });
  }

  private void removeMethod(UnusedMethod unusedMethod) {
    PsiMethod method = unusedMethod.getPsiMethod();
    if (method == null) return;

    ApplicationManager.getApplication().runWriteAction(() -> {
      try {
        method.delete();
      } catch (Exception e) {
        throw new RuntimeException("Failed to remove method", e);
      }
    });
  }

  private void removeClass(UnusedClass unusedClass) {
    com.intellij.psi.PsiClass psiClass = unusedClass.getPsiClass();
    if (psiClass == null) return;

    ApplicationManager.getApplication().runWriteAction(() -> {
      try {
        psiClass.delete();
      } catch (Exception e) {
        throw new RuntimeException("Failed to remove class", e);
      }
    });
  }

  private void copyToClipboard() {
    TreePath selectedPath = resultTree.getSelectionPath();
    if (selectedPath == null) return;

    Object lastComponent = selectedPath.getLastPathComponent();
    if (lastComponent instanceof DefaultMutableTreeNode) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) lastComponent;
      Object userObject = node.getUserObject();

      String text = "";
      if (userObject instanceof UnusedImport) {
        UnusedImport imp = (UnusedImport) userObject;
        text = String.format("%s at line %d in %s",
                imp.getImportText(),
                imp.getLineNumber() + 1,
                imp.getFilePath());
      } else if (userObject instanceof UnusedMethod) {
        UnusedMethod method = (UnusedMethod) userObject;
        text = String.format("%s in %s", method.getFullName(), method.getFilePath());
      } else if (userObject instanceof UnusedClass) {
        UnusedClass cls = (UnusedClass) userObject;
        text = String.format("%s in %s", cls.getFullName(), cls.getFilePath());
      }

      if (!text.isEmpty()) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        setStatus("Copied to clipboard");
      }
    }
  }

  private void refreshAnalysis() {
    setStatus("Refreshing...");
    // You can trigger a new analysis here
  }

  private void exportResults() {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setSelectedFile(new java.io.File("unused-code-report.txt"));
    int result = fileChooser.showSaveDialog(panel);

    if (result == JFileChooser.APPROVE_OPTION) {
      java.io.File file = fileChooser.getSelectedFile();
      try (FileWriter writer = new FileWriter(file)) {
        exportToWriter(writer);
        setStatus("Results exported to: " + file.getName());
      } catch (Exception ex) {
        setStatus("Error exporting: " + ex.getMessage());
      }
    }
  }

  private void exportToWriter(FileWriter writer) throws IOException {
    writer.write("Unused Code Detector Report\n");
    writer.write("Generated: " + new java.util.Date() + "\n");
    writer.write("===========================\n\n");

    for (int i = 0; i < rootNode.getChildCount(); i++) {
      DefaultMutableTreeNode categoryNode = (DefaultMutableTreeNode) rootNode.getChildAt(i);
      String categoryName = categoryNode.getUserObject().toString();
      writer.write(categoryName + "\n");
      writer.write("-".repeat(Math.min(50, categoryName.length())) + "\n\n");

      for (int j = 0; j < categoryNode.getChildCount(); j++) {
        DefaultMutableTreeNode itemNode = (DefaultMutableTreeNode) categoryNode.getChildAt(j);
        Object obj = itemNode.getUserObject();

        if (obj instanceof UnusedImport) {
          UnusedImport imp = (UnusedImport) obj;
          writer.write(String.format("  • %s (line %d)\n    Location: %s\n\n",
                  imp.getImportText(),
                  imp.getLineNumber() + 1,
                  imp.getFilePath()));
        } else if (obj instanceof UnusedMethod) {
          UnusedMethod method = (UnusedMethod) obj;
          writer.write(String.format("  • %s\n    Location: %s\n\n",
                  method.getFullName(),
                  method.getFilePath()));
        } else if (obj instanceof UnusedClass) {
          UnusedClass cls = (UnusedClass) obj;
          writer.write(String.format("  • %s\n    Location: %s\n\n",
                  cls.getFullName(),
                  cls.getFilePath()));
        } else {
          writer.write("  • " + obj.toString() + "\n\n");
        }
      }
    }

    writer.write("\n=== End of Report ===\n");
  }

  public void setResults(List<UnusedClass> classes,
                         List<UnusedMethod> methods,
                         List<UnusedImport> imports,
                         List<DuplicateImport> duplicates) {
    SwingUtilities.invokeLater(() -> {
      System.out.println("=== UnusedCodeToolWindowPanel.setResults ===");
      System.out.println("Classes: " + classes.size());
      System.out.println("Methods: " + methods.size());
      System.out.println("Imports: " + imports.size());
      System.out.println("Duplicates: " + duplicates.size());

      // Clear existing nodes
      if (rootNode == null) {
        System.err.println("ERROR: rootNode is null!");
        return;
      }

      rootNode.removeAllChildren();

      // 1. Add DUPLICATE IMPORTS node FIRST (most important)
      if (!duplicates.isEmpty()) {
        System.out.println("Adding Duplicate Imports node with " + duplicates.size() + " items");
        DefaultMutableTreeNode duplicateNode = new DefaultMutableTreeNode("⚠️ Duplicate Imports (" + duplicates.size() + ")");
        for (DuplicateImport duplicate : duplicates) {
          DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(duplicate);
          duplicateNode.add(itemNode);
          System.out.println("  Added duplicate: " + duplicate.getImportText() +
                  " (lines: " + duplicate.getLineNumbers() + ")");
        }
        rootNode.add(duplicateNode);
      }

      // 2. Add UNUSED IMPORTS node
      if (!imports.isEmpty()) {
        System.out.println("Adding Unused Imports node with " + imports.size() + " items");
        DefaultMutableTreeNode importNode = new DefaultMutableTreeNode("📦 Unused Imports (" + imports.size() + ")");
        for (UnusedImport unusedImport : imports) {
          DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(unusedImport);
          importNode.add(itemNode);
          System.out.println("  Added import: " + unusedImport.getImportText());
        }
        rootNode.add(importNode);
      }

      // 3. Add UNUSED CLASSES node
      if (!classes.isEmpty()) {
        System.out.println("Adding Unused Classes node with " + classes.size() + " items");
        DefaultMutableTreeNode classNode = new DefaultMutableTreeNode("📁 Unused Classes (" + classes.size() + ")");
        for (UnusedClass unusedClass : classes) {
          classNode.add(new DefaultMutableTreeNode(unusedClass));
        }
        rootNode.add(classNode);
      }

      // 4. Add UNUSED METHODS node
      if (!methods.isEmpty()) {
        System.out.println("Adding Unused Methods node with " + methods.size() + " items");
        DefaultMutableTreeNode methodNode = new DefaultMutableTreeNode("🔧 Unused Methods (" + methods.size() + ")");
        for (UnusedMethod method : methods) {
          methodNode.add(new DefaultMutableTreeNode(method));
        }
        rootNode.add(methodNode);
      }

      // Show message if nothing found
      if (classes.isEmpty() && methods.isEmpty() && imports.isEmpty() && duplicates.isEmpty()) {
        System.out.println("No issues found, showing empty message");
        DefaultMutableTreeNode emptyNode = new DefaultMutableTreeNode("✨ No issues found! ✨");
        rootNode.add(emptyNode);
      }

      // Refresh the tree model
      System.out.println("Refreshing tree model...");
      ((DefaultTreeModel) resultTree.getModel()).reload();

      // Expand all nodes to show results
      expandAll();

      // Force repaint
      resultTree.repaint();

      // Update status bar
      updateStatusBar(classes, methods, imports, duplicates);

      System.out.println("=== setResults complete ===");
      System.out.println("Total root children: " + rootNode.getChildCount());
    });
  }

  private void updateStatusBar(List<UnusedClass> classes,
                               List<UnusedMethod> methods,
                               List<UnusedImport> imports,
                               List<DuplicateImport> duplicates) {
    StringBuilder status = new StringBuilder();

    if (!duplicates.isEmpty()) {
      status.append(duplicates.size()).append(" duplicate imports, ");
    }
    if (!imports.isEmpty()) {
      status.append(imports.size()).append(" unused imports, ");
    }
    if (!classes.isEmpty()) {
      status.append(classes.size()).append(" classes, ");
    }
    if (!methods.isEmpty()) {
      status.append(methods.size()).append(" methods, ");
    }

    if (status.length() > 0) {
      if (status.toString().endsWith(", ")) {
        status.setLength(status.length() - 2);
      }
      status.insert(0, "Found: ");
    } else {
      status.append("No issues found");
    }

    statusLabel.setText(status.toString());
  }
}
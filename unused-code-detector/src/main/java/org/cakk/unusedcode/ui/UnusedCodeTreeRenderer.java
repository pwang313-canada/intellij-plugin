// src/main/java/org/cakk/unusedcode/ui/UnusedCodeTreeRenderer.java
package org.cakk.unusedcode.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.cakk.unusedcode.models.*;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

public class UnusedCodeTreeRenderer extends ColoredTreeCellRenderer {

  @Override
  public void customizeCellRenderer(JTree tree, Object value, boolean selected,
                                    boolean expanded, boolean leaf, int row, boolean hasFocus) {

    if (!(value instanceof DefaultMutableTreeNode node)) {
      return;
    }

    Object userObject = node.getUserObject();

    if (userObject instanceof DuplicateImport) {
      renderDuplicateImport((DuplicateImport) userObject);
    } else if (userObject instanceof UnusedImport) {
      renderUnusedImport((UnusedImport) userObject);
    } else if (userObject instanceof UnusedClass) {
      renderUnusedClass((UnusedClass) userObject);
    } else if (userObject instanceof UnusedMethod) {
      renderUnusedMethod((UnusedMethod) userObject);
    }
    else if (userObject instanceof UnusedVariable) {   // ← ADD THIS
      renderUnusedVariable((UnusedVariable) userObject);
    }
    else if (userObject instanceof String text) {
      // Category headers
      if (text.contains("Duplicate Imports")) {
        setIcon(AllIcons.General.Warning);
      } else if (text.contains("Unused Variables")) {
        setIcon(AllIcons.Nodes.Field);           // nice icon for variables
      } else if (text.contains("Unused Methods")) {
        setIcon(AllIcons.Nodes.Method);
      } else if (text.contains("Unused Classes")) {
        setIcon(AllIcons.Nodes.Class);
      }

      append(text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
    } else {
      // Fallback for unknown objects
      append(userObject.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
    }
  }

  // ==================== NEW: Render Unused Variable ====================
  private void renderUnusedVariable(UnusedVariable variable) {
    setIcon(AllIcons.Nodes.Field);   // or AllIcons.Nodes.Variable if you prefer

    append(variable.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);

    // Show class name and file
    String filePath = variable.getFilePath();
    String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);

    append(" in " + variable.getClassName() + " (" + fileName + ")",
            SimpleTextAttributes.GRAY_ATTRIBUTES);

    // Show line number
    append(" : line " + (variable.getLineNumber() + 1),
            SimpleTextAttributes.GRAY_ATTRIBUTES);

    // Tooltip
    setToolTipText(String.format("Unused Variable: %s\nClass: %s\nFile: %s\nLine: %d",
            variable.getName(),
            variable.getClassName(),
            variable.getFilePath(),
            variable.getLineNumber() + 1));
  }

  // Keep your existing render methods (renderUnusedClass, renderUnusedMethod, etc.)
  private void renderUnusedClass(UnusedClass unusedClass) {
    setIcon(AllIcons.Nodes.Class);
    append(unusedClass.getClassName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
    String fileName = getFileName(unusedClass.getFilePath());
    append(" (" + fileName + ")", SimpleTextAttributes.GRAY_ATTRIBUTES);
  }

  private void renderUnusedMethod(UnusedMethod method) {
    setIcon(AllIcons.Nodes.Method);
    append(method.getMethodName() + "()", SimpleTextAttributes.REGULAR_ATTRIBUTES);
    String fileName = getFileName(method.getFilePath());
    append(" in " + method.getContainingClass() + " (" + fileName + ")",
            SimpleTextAttributes.GRAY_ATTRIBUTES);
  }

  private void renderUnusedImport(UnusedImport unusedImport) {
    setIcon(AllIcons.FileTypes.Java);
    append(unusedImport.getImportText(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
    String fileName = getFileName(unusedImport.getFilePath());
    append(" (line " + (unusedImport.getLineNumber() + 1) + " in " + fileName + ")",
            SimpleTextAttributes.GRAY_ATTRIBUTES);
  }

  private void renderDuplicateImport(DuplicateImport duplicateImport) {
    setIcon(AllIcons.General.Warning);
    append(duplicateImport.getImportText(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
    append(" (duplicated " + duplicateImport.getDuplicateCount() + " times)",
            SimpleTextAttributes.GRAY_ATTRIBUTES);
  }

  // Helper method
  private String getFileName(String fullPath) {
    if (fullPath == null) return "unknown";
    int lastSlash = fullPath.lastIndexOf('/');
    return lastSlash != -1 ? fullPath.substring(lastSlash + 1) : fullPath;
  }
}
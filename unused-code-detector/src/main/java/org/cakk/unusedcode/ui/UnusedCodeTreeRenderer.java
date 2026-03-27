// src/main/java/org/cakk/unusedcode/ui/UnusedCodeTreeRenderer.java
package org.cakk.unusedcode.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.cakk.unusedcode.models.DuplicateImport;
import org.cakk.unusedcode.models.UnusedClass;
import org.cakk.unusedcode.models.UnusedImport;
import org.cakk.unusedcode.models.UnusedMethod;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

public class UnusedCodeTreeRenderer extends ColoredTreeCellRenderer {

  @Override
  public void customizeCellRenderer(JTree tree, Object value, boolean selected,
                                    boolean expanded, boolean leaf, int row, boolean hasFocus) {

    if (value instanceof DefaultMutableTreeNode) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
      Object userObject = node.getUserObject();

      if (userObject instanceof DuplicateImport) {
        renderDuplicateImport((DuplicateImport) userObject);
      }
      else if (userObject instanceof UnusedImport) {
        renderUnusedImport((UnusedImport) userObject);
      }
      else if (userObject instanceof UnusedClass) {
        renderUnusedClass((UnusedClass) userObject);
      }
      else if (userObject instanceof UnusedMethod) {
        renderUnusedMethod((UnusedMethod) userObject);
      }
      else if (userObject instanceof String text) {
        // Category headers
        if (text.contains("Duplicate Imports")) {
          setIcon(AllIcons.General.Warning);
          append(text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        } else {
          append(text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        }
      }
    }
  }

  private void renderUnusedClass(UnusedClass unusedClass) {
    setIcon(AllIcons.Nodes.Class);
    append(unusedClass.getClassName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);

    // Show file path
    String fileName = unusedClass.getFilePath();
    int lastSlash = fileName.lastIndexOf('/');
    if (lastSlash != -1) {
      fileName = fileName.substring(lastSlash + 1);
    }
    append(" (" + fileName + ")", SimpleTextAttributes.GRAY_ATTRIBUTES);
  }

  private void renderUnusedMethod(UnusedMethod method) {
    setIcon(AllIcons.Nodes.Method);
    append(method.getMethodName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
    append("()", SimpleTextAttributes.REGULAR_ATTRIBUTES);

    // Show containing class and file
    String filePath = method.getFilePath();
    String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
    append(" in " + method.getContainingClass() + " (" + fileName + ")",
            SimpleTextAttributes.GRAY_ATTRIBUTES);
  }

  private void renderUnusedImport(UnusedImport unusedImport) {
    setIcon(AllIcons.FileTypes.Java);

    // Show the import text
    append(unusedImport.getImportText(), SimpleTextAttributes.REGULAR_ATTRIBUTES);

    // Show line number and file
    String filePath = unusedImport.getFilePath();
    String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
    append(" (line " + (unusedImport.getLineNumber() + 1) + " in " + fileName + ")",
            SimpleTextAttributes.GRAY_ATTRIBUTES);

    // Set tooltip
    setToolTipText(String.format("File: %s%nLine: %d%nImport: %s",
            filePath,
            unusedImport.getLineNumber() + 1,
            unusedImport.getImportText()));
  }

  private void renderDuplicateImport(DuplicateImport duplicateImport) {
    setIcon(AllIcons.General.Warning);

    // Show the import text
    append(duplicateImport.getImportText(), SimpleTextAttributes.REGULAR_ATTRIBUTES);

    // Show duplicate count
    append(" (duplicated " + duplicateImport.getDuplicateCount() + " times)",
            SimpleTextAttributes.GRAY_ATTRIBUTES);

    // Show line numbers
    List<Integer> lineNumbers = duplicateImport.getLineNumbers();
    StringBuilder lines = new StringBuilder(" at lines: ");
    for (int i = 0; i < lineNumbers.size(); i++) {
      if (i > 0) lines.append(", ");
      lines.append(lineNumbers.get(i) + 1);
    }
    append(lines.toString(), SimpleTextAttributes.GRAY_ATTRIBUTES);

    // Set tooltip
    setToolTipText(String.format("Duplicate import: %s%nAppears at lines: %s%nFile: %s",
            duplicateImport.getImportText(),
            lines.toString(),
            duplicateImport.getFilePath()));
  }
}
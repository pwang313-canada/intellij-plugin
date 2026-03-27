// src/main/java/org/cakk/unusedcode/ui/DuplicateImportTreeRenderer.java
package org.cakk.unusedcode.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.cakk.unusedcode.models.DuplicateImport;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

public class DuplicateImportTreeRenderer extends ColoredTreeCellRenderer {

  @Override
  public void customizeCellRenderer(JTree tree, Object value, boolean selected,
                                    boolean expanded, boolean leaf, int row, boolean hasFocus) {

    if (value instanceof DefaultMutableTreeNode) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
      Object userObject = node.getUserObject();

      if (userObject instanceof DuplicateImport) {
        renderDuplicateImport((DuplicateImport) userObject);
      } else if (userObject instanceof String) {
        append((String) userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
      }
    }
  }

  private void renderDuplicateImport(DuplicateImport duplicateImport) {
    setIcon(AllIcons.FileTypes.Java);

    // Show the import text and duplicate count
    append(duplicateImport.getImportText(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
    append(" (duplicated " + duplicateImport.getDuplicateCount() + " times)",
            SimpleTextAttributes.GRAY_ATTRIBUTES);

    // Show line numbers
    List<Integer> lineNumbers = duplicateImport.getLineNumbers();
    StringBuilder lines = new StringBuilder(" lines: ");
    for (int i = 0; i < lineNumbers.size(); i++) {
      if (i > 0) lines.append(", ");
      lines.append(lineNumbers.get(i) + 1);
    }
    append(lines.toString(), SimpleTextAttributes.GRAY_ATTRIBUTES);

    // Set tooltip
    setToolTipText(String.format("File: %s%n%s",
            duplicateImport.getFilePath(),
            duplicateImport.getDuplicateInfo()));
  }
}
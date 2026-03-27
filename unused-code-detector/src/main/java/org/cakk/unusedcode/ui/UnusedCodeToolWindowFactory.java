// src/main/java/org/cakk/unusedcode/ui/UnusedCodeToolWindowFactory.java
package org.cakk.unusedcode.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating the Unused Code Detector tool window
 */
public class UnusedCodeToolWindowFactory implements ToolWindowFactory {

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    // Get or create the tool window service
    UnusedCodeToolWindow toolWindowService = UnusedCodeToolWindow.getInstance(project);

    // Add content if not already added
    if (toolWindow.getContentManager().getContentCount() == 0) {
      ContentFactory contentFactory = ContentFactory.getInstance();
      Content content = contentFactory.createContent(
              toolWindowService.getPanel().getPanel(),
              "Unused Code",
              false
      );
      toolWindow.getContentManager().addContent(content);
    }
  }

  @Override
  public boolean shouldBeAvailable(@NotNull Project project) {
    // Tool window is always available
    return true;
  }
}
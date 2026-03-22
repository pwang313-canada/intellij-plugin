// src/main/java/org/cakk/memoryleak/ui/MemoryMonitorToolWindowFactory.java
package org.cakk.memoryleak.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class MemoryMonitorToolWindowFactory implements ToolWindowFactory {

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    MemoryMonitorToolWindow toolWindowContent = new MemoryMonitorToolWindow(project);
    Content content = ContentFactory.getInstance()
            .createContent(toolWindowContent, "", false);
    toolWindow.getContentManager().addContent(content);
  }
}
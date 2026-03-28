package org.cakk.unusedcode.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class UnusedCodeToolWindowFactory implements ToolWindowFactory {

  public static final Key<UnusedCodeToolWindowPanel> KEY = Key.create("UnusedCodeToolWindowPanel");

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    UnusedCodeToolWindowPanel panel = new UnusedCodeToolWindowPanel(project);
    project.putUserData(KEY, panel);
    Content content = ContentFactory.getInstance().createContent(panel.getPanel(), "Results", false);
    toolWindow.getContentManager().addContent(content);
  }

  @Override
  public boolean shouldBeAvailable(@NotNull Project project) {
    return true;
  }
}
package org.cakk.propertytool.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class PropertiesValidatorToolWindowFactory implements ToolWindowFactory {
  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    ResultsToolWindow resultsToolWindow = project.getService(ResultsToolWindow.class);
    Content content = ContentFactory.getInstance()
            .createContent(resultsToolWindow.getComponent(), "", false);
    toolWindow.getContentManager().addContent(content);
  }
}
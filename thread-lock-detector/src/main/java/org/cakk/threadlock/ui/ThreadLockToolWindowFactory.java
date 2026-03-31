package org.cakk.threadlock.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class ThreadLockToolWindowFactory implements ToolWindowFactory {

  public static final com.intellij.openapi.util.Key<ThreadLockToolWindowPanel> KEY =
          com.intellij.openapi.util.Key.create("THREAD_LOCK_PANEL");

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {

    // ✅ use correct constructor
    ThreadLockToolWindowPanel panel = new ThreadLockToolWindowPanel(project);

    project.putUserData(KEY, panel);

    // ✅ use correct method name
    Content content = ContentFactory.getInstance()
            .createContent(panel.getContent(), "Results", false);

    toolWindow.getContentManager().addContent(content);
  }
}
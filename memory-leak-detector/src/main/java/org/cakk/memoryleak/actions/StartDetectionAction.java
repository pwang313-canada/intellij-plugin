// src/main/java/org/cakk/memoryleak/actions/StartDetectionAction.java
package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.NotNull;

public class StartDetectionAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      MemoryMonitorService service = project.getService(MemoryMonitorService.class);
      service.startMonitoring();
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      MemoryMonitorService service = project.getService(MemoryMonitorService.class);
      e.getPresentation().setEnabled(!service.isMonitoring());
      e.getPresentation().setVisible(true);
    } else {
      e.getPresentation().setEnabled(false);
    }
  }
}
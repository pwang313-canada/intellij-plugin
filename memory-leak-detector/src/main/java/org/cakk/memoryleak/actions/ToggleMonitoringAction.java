// src/main/java/org/cakk/memoryleak/actions/ToggleMonitoringAction.java
package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.NotNull;

public class ToggleMonitoringAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      MemoryMonitorService service = project.getService(MemoryMonitorService.class);
      if (service.isMonitoring()) {
        service.stopMonitoring();
      } else {
        service.startMonitoring();
      }
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      MemoryMonitorService service = project.getService(MemoryMonitorService.class);
      e.getPresentation().setText(service.isMonitoring() ?
              "Stop Memory Monitoring" : "Start Memory Monitoring");
      e.getPresentation().setVisible(true);
    } else {
      e.getPresentation().setEnabled(false);
    }
  }
}
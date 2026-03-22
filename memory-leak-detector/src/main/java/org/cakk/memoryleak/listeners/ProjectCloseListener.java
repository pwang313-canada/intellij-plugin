// src/main/java/org/cakk/memoryleak/listeners/ProjectCloseListener.java
package org.cakk.memoryleak.listeners;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.NotNull;

public class ProjectCloseListener implements ProjectManagerListener {

  @Override
  public void projectClosing(@NotNull Project project) {
    MemoryMonitorService monitorService = project.getService(MemoryMonitorService.class);
    if (monitorService != null && monitorService.isMonitoring()) {
      monitorService.stopMonitoring();
    }
  }
}
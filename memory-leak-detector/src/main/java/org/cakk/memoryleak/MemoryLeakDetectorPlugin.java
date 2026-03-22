// src/main/java/org/cakk/MemoryLeakDetectorPlugin.java
package org.cakk.memoryleak;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.cakk.memoryleak.settings.MemoryLeakSettings;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.NotNull;

public class MemoryLeakDetectorPlugin implements StartupActivity {

  @Override
  public void runActivity(@NotNull Project project) {
    try {
      // Initialize plugin services
      MemoryLeakSettings settings = MemoryLeakSettings.getInstance(project);

      // Auto-start monitoring if configured
      if (settings.isAutoStartMonitoring()) {
        MemoryMonitorService monitorService = project.getService(MemoryMonitorService.class);
        monitorService.startMonitoring();
      }
    } catch (Exception e) {
      // Log but don't crash IDEA
      System.err.println("Error initializing Memory Leak Detector: " + e.getMessage());
    }
  }
}
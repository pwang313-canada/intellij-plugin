package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.cakk.memoryleak.services.MemoryMonitorService;

public class CheckMemoryLeaksAction extends AnAction {

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    MemoryMonitorService monitor = project.getService(MemoryMonitorService.class);

    // Force GC to see what survives
    monitor.forceGc();

    // Run leak detection
    monitor.forceLeakDetection();

    Messages.showInfoMessage(
            project,
            "Memory leak analysis complete. Check the logs for results.",
            "Memory Leak Detection"
    );
  }
}
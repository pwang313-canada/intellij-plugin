// src/main/java/org/cakk/memoryleak/actions/DisconnectAction.java
package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;
import org.jetbrains.annotations.NotNull;

public class DisconnectAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      RemoteMemoryMonitorService remoteService = project.getService(RemoteMemoryMonitorService.class);
      remoteService.disconnect();
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      RemoteMemoryMonitorService remoteService = project.getService(RemoteMemoryMonitorService.class);
      e.getPresentation().setEnabled(remoteService.isConnected());
    }
  }
}
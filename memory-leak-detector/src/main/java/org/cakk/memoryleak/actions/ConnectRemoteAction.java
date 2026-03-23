// src/main/java/org/cakk/memoryleak/actions/ConnectRemoteAction.java
package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;
import org.cakk.memoryleak.ui.ProcessSelectionDialog;
import org.jetbrains.annotations.NotNull;

public class ConnectRemoteAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      RemoteMemoryMonitorService remoteService = project.getService(RemoteMemoryMonitorService.class);
      ProcessSelectionDialog dialog = new ProcessSelectionDialog(project, remoteService);
      dialog.show();
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      RemoteMemoryMonitorService remoteService = project.getService(RemoteMemoryMonitorService.class);
      e.getPresentation().setEnabled(!remoteService.isConnected());
    }
  }
}
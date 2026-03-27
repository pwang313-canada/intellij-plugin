package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;
import org.jetbrains.annotations.NotNull;

public class DisconnectFromRemoteAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    RemoteMemoryMonitorService remoteService = project.getService(RemoteMemoryMonitorService.class);
    MemoryMonitorService monitor = project.getService(MemoryMonitorService.class);

    if (remoteService != null && remoteService.isConnected()) {
      remoteService.disconnect();
      monitor.switchToLocalMode();

      Messages.showInfoMessage(
              project,
              "Disconnected from remote process.\n" +
                      "Memory monitoring is now tracking the IDE's local heap.",
              "Disconnected"
      );
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      e.getPresentation().setEnabled(false);
      return;
    }

    RemoteMemoryMonitorService remoteService = project.getService(RemoteMemoryMonitorService.class);
    e.getPresentation().setEnabled(remoteService != null && remoteService.isConnected());
    e.getPresentation().setText(remoteService != null && remoteService.isConnected() ?
            "Disconnect from Remote (PID: " + remoteService.getConnectedPid() + ")" :
            "Disconnect from Remote");
  }
}
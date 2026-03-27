package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;
import org.cakk.memoryleak.ui.ProcessSelectorDialog;
import org.jetbrains.annotations.NotNull;

public class ConnectToRemoteAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    RemoteMemoryMonitorService remoteService = project.getService(RemoteMemoryMonitorService.class);
    MemoryMonitorService monitor = project.getService(MemoryMonitorService.class);

    if (remoteService == null) {
      Messages.showErrorDialog(project, "Remote monitoring service not available", "Error");
      return;
    }

    // Show process selector dialog
    ProcessSelectorDialog dialog = new ProcessSelectorDialog(project, remoteService);
    dialog.show();

    String selectedPid = dialog.getSelectedProcessId();
    if (selectedPid != null) {
      try {
        if (remoteService.connectToProcess(selectedPid)) {
          // Switch monitor to remote mode
          monitor.switchToRemoteMode(selectedPid);

          Messages.showInfoMessage(
                  project,
                  "Connected to remote process: " + selectedPid + "\n\n" +
                          "Memory monitoring is now tracking the remote application.\n" +
                          "You will see alerts if memory leaks are detected.",
                  "Connected to Remote Process"
          );
        }
      } catch (Exception ex) {
        Messages.showErrorDialog(
                project,
                "Failed to connect: " + ex.getMessage() + "\n\n" +
                        "Make sure your application is running with JMX enabled:\n" +
                        "-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9020",
                "Connection Failed"
        );
      }
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabled(project != null);
  }
}
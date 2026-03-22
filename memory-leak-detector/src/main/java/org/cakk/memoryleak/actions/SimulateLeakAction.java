// src/main/java/org/cakk/memoryleak/actions/SimulateLeakAction.java
package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.cakk.memoryleak.ui.LeakSimulationDialog;
import org.jetbrains.annotations.NotNull;

public class SimulateLeakAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      LeakSimulationDialog dialog = new LeakSimulationDialog(project);
      dialog.show();
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabled(project != null);
    e.getPresentation().setVisible(true);
  }
}
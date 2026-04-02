package org.cakk.propertytool.actions;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.cakk.propertytool.analysis.AnalysisResult;
import org.cakk.propertytool.analysis.PropertiesAnalyzer;
import org.cakk.propertytool.ui.ResultsToolWindow;
import org.cakk.propertytool.util.Utils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static org.cakk.propertytool.util.Utils.isPropertiesFile;
import static org.cakk.propertytool.util.Utils.isPropertyFile;

public class AnalyzePropertiesAction extends AnAction {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    // Collect files on EDT (fast operation)
    List<VirtualFile> filesToAnalyze = new ArrayList<>();
    VirtualFile[] selectedFiles = e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
    if (selectedFiles != null && selectedFiles.length == 1) {
      VirtualFile selected = selectedFiles[0];
      if (selected.isDirectory()) {
        collectPropertiesFiles(selected, filesToAnalyze);
      } else if (isPropertiesFile(selected)) {
        filesToAnalyze.add(selected);
      }
    }

    if (filesToAnalyze.isEmpty()) return;

    // Run analysis on background thread
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      PropertiesAnalyzer analyzer = new PropertiesAnalyzer(project);
      List<AnalysisResult> results = analyzer.analyze(filesToAnalyze);

      // Update UI on EDT
      ApplicationManager.getApplication().invokeLater(() -> {
        ResultsToolWindow toolWindow = project.getService(ResultsToolWindow.class);
        toolWindow.setResults(results);
        toolWindow.show();
      });
    });
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    VirtualFile[] files = e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
    boolean enabled = false;
    if (files != null && files.length == 1) {
      VirtualFile vf = files[0];
      if (!vf.isDirectory()) {
        enabled = isPropertyFile(vf);
      } else {
        // Only enable for folders named "resources"
        enabled = vf.getName().equals("resources");
      }
    }
    e.getPresentation().setEnabledAndVisible(enabled);
  }

  private void collectPropertiesFiles(VirtualFile dir, List<VirtualFile> out) {
    for (VirtualFile child : dir.getChildren()) {
      if (child.isDirectory()) {
        collectPropertiesFiles(child, out);
      } else if (isPropertiesFile(child)) {
        out.add(child);
      }
    }
  }
}
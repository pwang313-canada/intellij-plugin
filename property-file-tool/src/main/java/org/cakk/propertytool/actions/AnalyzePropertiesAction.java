package org.cakk.propertytool.actions;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.actionSystem.*;
import org.cakk.propertytool.analysis.AnalysisResult;
import org.cakk.propertytool.analysis.PropertiesAnalyzer;
import org.cakk.propertytool.ui.ResultsToolWindow;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class AnalyzePropertiesAction extends AnAction {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    VirtualFile[] selectedFiles = e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
    if (selectedFiles == null || selectedFiles.length != 1) return;

    VirtualFile selected = selectedFiles[0];
    boolean checkCrossReferences;
    List<VirtualFile> filesToAnalyze = new ArrayList<>();

    if (selected.isDirectory()) {
      boolean isSourceRoot = isSourceRoot(selected, project);

      if (isSourceRoot) {
        checkCrossReferences = true;
        collectAllPropertiesFilesInProject(project, filesToAnalyze);
      } else {
        checkCrossReferences = false;
        collectPropertiesFiles(selected, filesToAnalyze);
      }
    } else if (isPropertiesFile(selected)) {
      filesToAnalyze.add(selected);
      checkCrossReferences = false;
    } else {
      checkCrossReferences = false;
    }

    if (filesToAnalyze.isEmpty()) return;

    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      PropertiesAnalyzer analyzer = new PropertiesAnalyzer(project);
      List<AnalysisResult> results = analyzer.analyze(filesToAnalyze, checkCrossReferences);

      // Filter: remove lineNumber < 1 and deduplicate per file + lineNumber
      Map<VirtualFile, Set<Integer>> seenLinesPerFile = new HashMap<>();
      List<AnalysisResult> filteredResults = new ArrayList<>();

      for (AnalysisResult result : results) {
        // Skip results with invalid line number (less than 1)
        if (result.getLineNumber() < 1) {
          continue;
        }

        // Get the set of already reported line numbers for this file
        Set<Integer> seenLines = seenLinesPerFile.computeIfAbsent(result.getFile(), k -> new HashSet<>());

        // Only add if this line number hasn't been reported for this file
        if (!seenLines.contains(result.getLineNumber())) {
          seenLines.add(result.getLineNumber());
          filteredResults.add(result);
        }
      }

      // Update the results with the filtered list
      ApplicationManager.getApplication().invokeLater(() -> {
        ResultsToolWindow toolWindow = project.getService(ResultsToolWindow.class);
        toolWindow.setResults(filteredResults);
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
      enabled = vf.isDirectory() || isPropertiesFile(vf);
    }
    e.getPresentation().setEnabledAndVisible(enabled);
  }

  private void collectPropertiesFiles(VirtualFile dir, List<VirtualFile> out) {
    if (dir == null || !dir.isDirectory()) return;
    VirtualFile[] children = dir.getChildren();
    if (children == null) return;

    for (VirtualFile child : children) {
      if (child.isDirectory()) {
        collectPropertiesFiles(child, out);
      } else if (isPropertiesFile(child)) {
        out.add(child);
      }
    }
  }

  private void collectAllPropertiesFilesInProject(Project project, List<VirtualFile> out) {
    VirtualFile[] sourceRoots = ProjectRootManager.getInstance(project).getContentSourceRoots();
    for (VirtualFile contentRoot : sourceRoots) {
      collectPropertiesFiles(contentRoot, out);
    }
  }

  private boolean isPropertiesFile(VirtualFile file) {
    String name = file.getName();
    return name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml");
  }

  private boolean isSourceRoot(VirtualFile dir, Project project) {
    Module[] modules = ModuleManager.getInstance(project).getModules();

    for (Module module : modules) {
      ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);

      // getSourceRoots(false) = production sources only (excludes test sources)
      for (VirtualFile sourceRoot : moduleRootManager.getSourceRoots(false)) {
        if (dir.equals(sourceRoot)) {
          return true;
        }
      }
    }
    return false;
  }
}
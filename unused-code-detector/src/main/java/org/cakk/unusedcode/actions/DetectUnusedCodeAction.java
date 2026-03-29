// src/main/java/org/cakk/unusedcode/actions/DetectUnusedCodeAction.java
package org.cakk.unusedcode.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import org.cakk.unusedcode.models.*;
import org.cakk.unusedcode.services.UnusedCodeAnalysisService;
import org.cakk.unusedcode.ui.UnusedCodeToolWindowFactory;
import org.cakk.unusedcode.ui.UnusedCodeToolWindowPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class DetectUnusedCodeAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    // Get the panel from the project (created by the tool window factory)
    UnusedCodeToolWindowPanel panel = project.getUserData(UnusedCodeToolWindowFactory.KEY);
    if (panel == null) {
      // If the tool window has never been opened, show the window first to create the panel
      ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Unused Code Detector");
      if (toolWindow != null) {
        toolWindow.show();
        // Try again after a short delay (the factory will have created the panel)
        SwingUtilities.invokeLater(() -> {
          UnusedCodeToolWindowPanel p = project.getUserData(UnusedCodeToolWindowFactory.KEY);
          if (p != null) {
            performAnalysis(e, p);
          } else {
            Messages.showErrorDialog(project, "Tool window not initialized. Please open it manually once.", "Error");
          }
        });
        return;
      } else {
        Messages.showErrorDialog(project, "Tool window not found. Please restart the IDE.", "Error");
        return;
      }
    }

    performAnalysis(e, panel);
  }

  private void performAnalysis(@NotNull AnActionEvent e, UnusedCodeToolWindowPanel panel) {
    Project project = e.getProject();
    if (project == null) return;

    VirtualFile virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
    PsiDirectory psiDirectory = null;
    if (virtualFile != null && virtualFile.isDirectory()) {
      psiDirectory = PsiManager.getInstance(project).findDirectory(virtualFile);
    }

    panel.clearResults();
    panel.setStatus("Analyzing...");

    UnusedCodeAnalysisService analysisService = new UnusedCodeAnalysisService(project);

    // Full analysis for a directory
    if (psiDirectory != null && isJavaSourceDirectory(psiDirectory, project)) {
      panel.setStatus("Analyzing directory: " + psiDirectory.getName());
      analyzeDirectory(analysisService, psiDirectory, panel, project);
    }
    // Single Java file → imports only
    else if (psiFile instanceof PsiJavaFile) {
      panel.setStatus("Analyzing imports in file: " + psiFile.getName());
      analyzeFileImportsOnly(analysisService, (PsiJavaFile) psiFile, panel);
    }
    else {
      panel.setStatus("Feature disabled: Please select a Java source folder or a Java file");
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      e.getPresentation().setEnabled(false);
      return;
    }

    VirtualFile virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
    PsiDirectory psiDirectory = null;
    if (virtualFile != null && virtualFile.isDirectory()) {
      psiDirectory = PsiManager.getInstance(project).findDirectory(virtualFile);
    }

    boolean isEnabled = false;
    String actionText = "Unused Code Detector";

    if (psiDirectory != null) {
      VirtualFile dirFile = psiDirectory.getVirtualFile();
      ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
      // Lightweight check: accept if it's a source root or a common source folder name
      if (fileIndex.isInSourceContent(dirFile) ||
              dirFile.getName().matches("java|src|main|test|generated")) {
        isEnabled = true;
        actionText = "Analyze Directory: " + psiDirectory.getName() + " (Classes, Methods, Imports)";
      }
    } else if (psiFile instanceof PsiJavaFile) {
      isEnabled = true;
      actionText = "Check Imports in: " + psiFile.getName();
    }

    e.getPresentation().setEnabled(isEnabled);
    e.getPresentation().setText(actionText);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    // update() only performs lightweight checks, so it can run on a background thread
    return ActionUpdateThread.BGT;
  }

  // -------------------------------------------------------------------------
  // Lightweight directory check for update()
  private boolean isJavaSourceDirectory(PsiDirectory directory, Project project) {
    VirtualFile virtualFile = directory.getVirtualFile();
    return isJavaSourceDirectory(virtualFile, project);
  }

  private boolean isJavaSourceDirectory(VirtualFile virtualFile, Project project) {
    ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
    if (fileIndex.isInSourceContent(virtualFile)) {
      return true;
    }
    String name = virtualFile.getName();
    return name.equals("java") || name.equals("src") || name.equals("main") ||
            name.equals("test") || name.equals("generated");
  }

  // -------------------------------------------------------------------------
  // Directory analysis (full scan)
  private void analyzeDirectory(UnusedCodeAnalysisService service,
                                PsiDirectory directory,
                                UnusedCodeToolWindowPanel panel,
                                Project project) {
    List<PsiJavaFile> javaFiles = new ArrayList<>();
    collectJavaFiles(directory, javaFiles);

    if (javaFiles.isEmpty()) {
      panel.setStatus("No Java files found in selected directory");
      return;
    }

    panel.setStatus(String.format("Analyzing %d Java files for classes, methods, and imports...", javaFiles.size()));
    service.analyzeFiles(javaFiles, new AnalysisCallbackImpl(panel, true));
  }

  private void collectJavaFiles(PsiDirectory directory, List<PsiJavaFile> javaFiles) {
    for (PsiFile file : directory.getFiles()) {
      if (file instanceof PsiJavaFile) {
        javaFiles.add((PsiJavaFile) file);
      }
    }
    for (PsiDirectory subdir : directory.getSubdirectories()) {
      collectJavaFiles(subdir, javaFiles);
    }
  }

  // -------------------------------------------------------------------------
  // Single file analysis (imports only)
  private void analyzeFileImportsOnly(UnusedCodeAnalysisService service,
                                      PsiJavaFile javaFile,
                                      UnusedCodeToolWindowPanel panel) {
    panel.setStatus(String.format("Analyzing imports in %s...", javaFile.getName()));
    service.analyzeFileImportsOnly(javaFile, new AnalysisCallbackImpl(panel, false));
  }

  // -------------------------------------------------------------------------
  // Callback that updates the UI panel
  private static class AnalysisCallbackImpl implements UnusedCodeAnalysisService.AnalysisCallback {
    private final UnusedCodeToolWindowPanel panel;
    private final boolean isFullAnalysis;

    AnalysisCallbackImpl(UnusedCodeToolWindowPanel panel, boolean isFullAnalysis) {
      this.panel = panel;
      this.isFullAnalysis = isFullAnalysis;
    }

    @Override
    public void onComplete(List<UnusedClass> classes,
                           List<UnusedMethod> methods,
                           List<UnusedImport> imports,
                           List<DuplicateImport> duplicates,
                           List<UnusedVariable> variables) {
      SwingUtilities.invokeLater(() -> {
        panel.setResults(classes, methods, imports, duplicates, variables);
        if (isFullAnalysis) {
          panel.setStatus(String.format("Analysis complete: %d classes, %d methods, %d unused imports, %d duplicate imports",
                  classes.size(), methods.size(), imports.size(), duplicates.size()));
        } else {
          panel.setStatus(String.format("Import analysis complete: %d unused imports, %d duplicate imports",
                  imports.size(), duplicates.size()));
        }
        // Optionally send a notification
        if (imports.size() + duplicates.size() > 0) {
          Notification notification = new Notification(
                  "Unused Code Detector",
                  "Analysis Complete",
                  String.format("Found %d unused imports and %d duplicate imports.", imports.size(), duplicates.size()),
                  NotificationType.INFORMATION
          );
          Notifications.Bus.notify(notification, panel.getProject());
        }
      });
    }

    @Override
    public void onError(String error) {
      SwingUtilities.invokeLater(() -> {
        panel.setStatus("Error: " + error);
        Notification notification = new Notification(
                "Unused Code Detector",
                "Analysis Error",
                error,
                NotificationType.ERROR
        );
        Notifications.Bus.notify(notification, panel.getProject());
      });
    }
  }
}
// src/main/java/org/cakk/unusedcode/actions/DetectUnusedCodeAction.java
package org.cakk.unusedcode.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.cakk.unusedcode.models.DuplicateImport;
import org.cakk.unusedcode.models.UnusedClass;
import org.cakk.unusedcode.models.UnusedImport;
import org.cakk.unusedcode.models.UnusedMethod;
import org.cakk.unusedcode.services.UnusedCodeAnalysisService;
import org.cakk.unusedcode.ui.UnusedCodeToolWindow;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.List;
import java.util.Map;


public class DetectUnusedCodeAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    // Get the selected elements
    VirtualFile virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

    // Get PsiDirectory if it's a directory
    PsiDirectory psiDirectory = null;
    if (virtualFile != null && virtualFile.isDirectory()) {
      psiDirectory = PsiManager.getInstance(project).findDirectory(virtualFile);
    }

    // Show the tool window
    UnusedCodeToolWindow toolWindow = UnusedCodeToolWindow.getInstance(project);
    if (toolWindow == null) {
      Messages.showErrorDialog(project, "Tool window not initialized", "Error");
      return;
    }

    toolWindow.show();
    toolWindow.clearResults();

    UnusedCodeAnalysisService analysisService = new UnusedCodeAnalysisService(project);

    // Determine what was selected and analyze accordingly
    if (psiDirectory != null && isJavaSourceDirectory(psiDirectory, project)) {
      // Right-clicked on a Java source folder - do full analysis (classes, methods, imports)
      toolWindow.setStatus("Analyzing directory: " + psiDirectory.getName());
      analyzeDirectory(analysisService, psiDirectory, toolWindow, project);
    }
    else if (psiFile instanceof PsiJavaFile) {
      // Right-clicked on a Java file - do imports only
      toolWindow.setStatus("Analyzing imports in file: " + psiFile.getName());
      analyzeFileImportsOnly(analysisService, (PsiJavaFile) psiFile, toolWindow);
    }
    else {
      // Disabled for other selections
      toolWindow.setStatus("Feature disabled: Please select a Java source folder or a Java file");
      toolWindow.show();
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      e.getPresentation().setEnabled(false);
      return;
    }

    // Get the selected elements
    VirtualFile virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

    // Get PsiDirectory from VirtualFile if it's a directory
    PsiDirectory psiDirectory = null;
    if (virtualFile != null && virtualFile.isDirectory()) {
      psiDirectory = PsiManager.getInstance(project).findDirectory(virtualFile);
    }

    boolean isEnabled = false;
    String actionText = "Unused Code Detector";

    if (psiDirectory != null && isJavaSourceDirectory(psiDirectory, project)) {
      // Java source folder - full analysis
      isEnabled = true;
      actionText = "Analyze Directory: " + psiDirectory.getName() + " (Classes, Methods, Imports)";
    }
    else if (psiFile instanceof PsiJavaFile) {
      // Java file - imports only
      isEnabled = true;
      actionText = "Check Imports in: " + psiFile.getName();
    }
    else {
      // Other selections - disabled
      isEnabled = false;
      actionText = "Unused Code Detector (Select Java Source Folder or Java File)";
    }

    e.getPresentation().setEnabled(isEnabled);
    e.getPresentation().setText(actionText);
  }

  private boolean isJavaSourceDirectory(PsiDirectory directory, Project project) {
    VirtualFile virtualFile = directory.getVirtualFile();
    return isJavaSourceDirectory(virtualFile, project);
  }

  private boolean isJavaSourceDirectory(VirtualFile virtualFile, Project project) {
    ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);

    // Check if this is in source content
    if (fileIndex.isInSourceContent(virtualFile)) {
      // Check if it contains Java files
      return containsJavaFiles(virtualFile);
    }

    // Check common source folder names
    String name = virtualFile.getName();
    return name.equals("java") || name.equals("src") || name.equals("main") ||
            name.equals("test") || name.equals("generated");
  }

  private boolean containsJavaFiles(VirtualFile directory) {
    if (!directory.isDirectory()) return false;

    for (VirtualFile child : directory.getChildren()) {
      if (child.isDirectory()) {
        if (containsJavaFiles(child)) return true;
      } else if (child.getName().endsWith(".java")) {
        return true;
      }
    }
    return false;
  }

  private void analyzeDirectory(UnusedCodeAnalysisService service, PsiDirectory directory,
                                UnusedCodeToolWindow toolWindow, Project project) {
    List<PsiJavaFile> javaFiles = new ArrayList<>();
    collectJavaFiles(directory, javaFiles);

    if (javaFiles.isEmpty()) {
      toolWindow.setStatus("No Java files found in selected directory");
      return;
    }

    toolWindow.setStatus(String.format("Analyzing %d Java files for classes, methods, and imports...", javaFiles.size()));

    // Full analysis for directory (classes, methods, imports)
    service.analyzeFiles(javaFiles, new AnalysisCallbackImpl(toolWindow, true));
  }

  private void analyzeFileImportsOnly(UnusedCodeAnalysisService service, PsiJavaFile javaFile,
                                      UnusedCodeToolWindow toolWindow) {
    toolWindow.setStatus(String.format("Analyzing imports in %s...", javaFile.getName()));

    // Imports-only analysis for single file
    service.analyzeFileImportsOnly(javaFile, new AnalysisCallbackImpl(toolWindow, false));
  }

  private void collectJavaFiles(PsiDirectory directory, List<PsiJavaFile> javaFiles) {
    // Check files in this directory
    for (PsiFile file : directory.getFiles()) {
      if (file instanceof PsiJavaFile) {
        javaFiles.add((PsiJavaFile) file);
      }
    }

    // Check subdirectories
    for (PsiDirectory subdir : directory.getSubdirectories()) {
      collectJavaFiles(subdir, javaFiles);
    }
  }

  // Update AnalysisCallbackImpl in DetectUnusedCodeAction.java
  private static class AnalysisCallbackImpl implements UnusedCodeAnalysisService.AnalysisCallback {
    private final UnusedCodeToolWindow toolWindow;
    private final boolean isFullAnalysis;

    AnalysisCallbackImpl(UnusedCodeToolWindow toolWindow, boolean isFullAnalysis) {
      this.toolWindow = toolWindow;
      this.isFullAnalysis = isFullAnalysis;
    }

    @Override
    public void onComplete(List<UnusedClass> classes,
                           List<UnusedMethod> methods,
                           List<UnusedImport> imports,
                           List<DuplicateImport> duplicates) {
      SwingUtilities.invokeLater(() -> {
        toolWindow.setResults(classes, methods, imports, duplicates);

        if (isFullAnalysis) {
          toolWindow.setStatus(String.format(
                  "Analysis complete: %d classes, %d methods, %d unused imports, %d duplicate imports",
                  classes.size(), methods.size(), imports.size(), duplicates.size()
          ));
        } else {
          toolWindow.setStatus(String.format(
                  "Import analysis complete: %d unused imports, %d duplicate imports found",
                  imports.size(), duplicates.size()
          ));
        }

        toolWindow.show();
      });
    }

    @Override
    public void onError(String error) {
      SwingUtilities.invokeLater(() -> {
        toolWindow.setStatus("Error: " + error);
        toolWindow.show();
      });
    }
  }}
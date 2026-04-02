package org.cakk.threadlock.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import org.cakk.threadlock.services.ThreadLockAnalysisService;
import org.cakk.threadlock.ui.ThreadLockToolWindowFactory;
import org.cakk.threadlock.ui.ThreadLockToolWindowPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class AnalyzeThreadLocksAction extends AnAction implements DumbAware {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    ThreadLockToolWindowPanel panel = project.getUserData(ThreadLockToolWindowFactory.KEY);
    if (panel == null) {
      ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Thread Lock Detector");
      if (toolWindow != null) {
        toolWindow.show();
      }
    }
    // Schedule the analysis on EDT with a write intent lock
    ApplicationManager.getApplication().invokeLater(() -> performAnalysis(e, project));
  }

  private void performAnalysis(AnActionEvent e, Project project) {
    ThreadLockToolWindowPanel panel = project.getUserData(ThreadLockToolWindowFactory.KEY);
    if (panel == null) return;

    panel.clearResults();
    panel.setStatus("Analyzing for thread lock issues...");

    ThreadLockAnalysisService service = new ThreadLockAnalysisService(project);

    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
    PsiDirectory directory = null;

    if (psiFile instanceof PsiJavaFile) {
      service.analyzeFile((PsiJavaFile) psiFile, panel);
    } else {
      // Try directory
      var virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
      if (virtualFile != null && virtualFile.isDirectory()) {
        directory = PsiManager.getInstance(project).findDirectory(virtualFile);
      }
      if (directory != null) {
        service.analyzeDirectory(directory, panel);
      } else {
        panel.setStatus("Please select a Java file or source directory");
      }
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

    if (psiDirectory != null) {
      VirtualFile dirFile = psiDirectory.getVirtualFile();
      ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
      // Lightweight check: accept if it's a source root or a common source folder name
      if (fileIndex.isInSourceContent(dirFile) ||
              dirFile.getName().matches("java|src|main|test")) {
        isEnabled = true;
      }
    } else if (psiFile instanceof PsiJavaFile) {
      isEnabled = true;
    }

    e.getPresentation().setEnabled(isEnabled);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    // update() only performs lightweight checks, so it can run on a background thread
    return ActionUpdateThread.BGT;
  }
}
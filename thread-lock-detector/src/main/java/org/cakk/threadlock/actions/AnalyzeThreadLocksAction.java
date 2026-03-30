package org.cakk.threadlock.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
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
      ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Thread Lock Checker");
      if (toolWindow != null) {
        toolWindow.show();
        SwingUtilities.invokeLater(() -> performAnalysis(e, project));
      }
      return;
    }

    performAnalysis(e, project);
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
    // Enable only on Java files or Java source directories
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    var virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

    boolean enabled = false;

    if (file instanceof PsiJavaFile) {
      enabled = true; // single Java file
    } else if (virtualFile != null && virtualFile.isDirectory()) {
      enabled = true; // directory
    }

    //e.getPresentation().setEnabled(enabled);
    e.getPresentation().setEnabled(true);
    // Or log the data
  }
}
package org.cakk.threadlock.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import org.cakk.threadlock.services.StaticThreadLockAnalysisService;
import org.cakk.threadlock.ui.ThreadLockToolWindowFactory;
import org.cakk.threadlock.ui.ThreadLockToolWindowPanel;
import org.jetbrains.annotations.NotNull;

public class AnalyzeRuntimeThreadLocksAction extends AnAction implements DumbAware {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    // Ensure the tool window is created and shown
    ThreadLockToolWindowPanel panel = project.getUserData(ThreadLockToolWindowFactory.KEY);
    if (panel == null) {
      ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Thread Lock Detector");
      if (toolWindow != null) {
        toolWindow.show();
      }
    }

    // Schedule analysis on EDT with write intent lock (so read actions are allowed)
    ApplicationManager.getApplication().invokeLater(() -> performAnalysis(e, project));
  }

  private void performAnalysis(AnActionEvent e, Project project) {
    ThreadLockToolWindowPanel panel = project.getUserData(ThreadLockToolWindowFactory.KEY);
    if (panel == null) return;

    panel.clearResults();
    panel.setStatus("Analyzing for thread lock issues...");

    StaticThreadLockAnalysisService service = new StaticThreadLockAnalysisService(project);

    // Try to get PSI file (works for editor selections)
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
    if (psiFile instanceof PsiJavaFile) {
      service.analyzeFile((PsiJavaFile) psiFile, panel);
      return;
    }

    // Otherwise, look for a VirtualFile (directory or file) from the data context
    VirtualFile vFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
    VirtualFile[] vFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    PsiDirectory directory = null;

    if (vFile != null) {
      if (vFile.isDirectory()) {
        directory = PsiManager.getInstance(project).findDirectory(vFile);
      } else if (vFile.getName().endsWith(".java")) {
        // It's a Java file, but not represented as PSI yet. Convert it.
        psiFile = PsiManager.getInstance(project).findFile(vFile);
        if (psiFile instanceof PsiJavaFile) {
          service.analyzeFile((PsiJavaFile) psiFile, panel);
          return;
        }
      }
    } else if (vFiles != null && vFiles.length == 1) {
      VirtualFile file = vFiles[0];
      if (file.isDirectory()) {
        directory = PsiManager.getInstance(project).findDirectory(file);
      } else if (file.getName().endsWith(".java")) {
        psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile instanceof PsiJavaFile) {
          service.analyzeFile((PsiJavaFile) psiFile, panel);
          return;
        }
      }
    }

    // If we have a directory, analyze it
    if (directory != null) {
      service.analyzeDirectory(directory, panel);
    } else {
      panel.setStatus("Please select a Java file or source directory");
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      e.getPresentation().setEnabled(false);
      return;
    }

    boolean enabled = false;

    // Check PSI file (editor selection)
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
    if (psiFile instanceof PsiJavaFile) {
      enabled = true;
    }

    // Check single VirtualFile (project view)
    if (!enabled) {
      VirtualFile vFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
      if (vFile != null) {
        enabled = vFile.isDirectory() || vFile.getName().endsWith(".java");
      }
    }

    // Check VirtualFile array (project view, multi‑selection)
    if (!enabled) {
      VirtualFile[] vFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
      if (vFiles != null && vFiles.length == 1) {
        VirtualFile file = vFiles[0];
        enabled = file.isDirectory() || file.getName().endsWith(".java");
      }
    }

    // Fallback: if editor is active but no explicit selection, use the open file
    if (!enabled) {
      Editor editor = e.getData(CommonDataKeys.EDITOR);
      if (editor != null) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        enabled = file != null && file.getName().endsWith(".java");
      }
    }

    e.getPresentation().setEnabled(enabled);
    e.getPresentation().setVisible(true);
  }
}
package org.cakk.threadlock.services;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.cakk.threadlock.models.ThreadLockIssue;
import org.cakk.threadlock.ui.ThreadLockToolWindowPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class ThreadLockAnalysisService {

  private final Project project;

  public ThreadLockAnalysisService(Project project) {
    this.project = project;
  }

  public void analyzeDirectory(PsiDirectory directory, ThreadLockToolWindowPanel panel) {
    List<PsiJavaFile> javaFiles = collectJavaFiles(directory);
    if (javaFiles.isEmpty()) {
      SwingUtilities.invokeLater(() -> panel.setStatus("No Java files found in the directory."));
      return;
    }

    new Task.Backgroundable(project, "Analyzing Thread Locks", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        List<ThreadLockIssue> issues = new ArrayList<>();
        int total = javaFiles.size();
        int processed = 0;

        for (PsiJavaFile file : javaFiles) {
          if (indicator.isCanceled()) break;
          processed++;
          indicator.setFraction((double) processed / total);
          indicator.setText("Analyzing: " + file.getName());

          issues.addAll(analyzeSingleFile(file));
        }

        SwingUtilities.invokeLater(() -> panel.updateResults(issues));
      }
    }.queue();
  }

  public void analyzeFile(PsiJavaFile javaFile, ThreadLockToolWindowPanel panel) {
    new Task.Backgroundable(project, "Analyzing Thread Locks", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        List<ThreadLockIssue> issues = analyzeSingleFile(javaFile);
        SwingUtilities.invokeLater(() -> panel.updateResults(issues));
      }
    }.queue();
  }

  private List<ThreadLockIssue> analyzeSingleFile(PsiJavaFile javaFile) {
    List<ThreadLockIssue> issues = new ArrayList<>();

    ReadAction.run(() -> {
      // 1. Check synchronized statements
      javaFile.accept(new JavaRecursiveElementVisitor() {
        @Override
        public void visitSynchronizedStatement(@NotNull PsiSynchronizedStatement statement) {
          super.visitSynchronizedStatement(statement);
          checkSynchronizedStatement(statement, issues, javaFile);
        }

        @Override
        public void visitMethod(@NotNull PsiMethod method) {
          super.visitMethod(method);
          if (method.hasModifierProperty(PsiModifier.SYNCHRONIZED)) {
            issues.add(new ThreadLockIssue(
                    "SynchronizedMethod",
                    "Synchronized method can hold lock longer than necessary",
                    method.getModifierList(),
                    javaFile,
                    getLineNumber(method.getModifierList()),
                    ThreadLockIssue.Severity.WARNING
            ));
          }
        }
      });
    });

    return issues;
  }


  private void checkSynchronizedStatement(PsiSynchronizedStatement stmt,
                                          List<ThreadLockIssue> issues,
                                          PsiJavaFile javaFile) {
    PsiExpression lockExpr = stmt.getLockExpression();
    if (lockExpr == null) return;

    String lockText = lockExpr.getText().trim();

    if ("this".equals(lockText)) {
      issues.add(new ThreadLockIssue(
              "SynchronizedThis",
              "Synchronizing on 'this' is discouraged. Use a private final lock object instead.",
              lockExpr,
              javaFile,
              getLineNumber(lockExpr),
              ThreadLockIssue.Severity.WARNING
      ));
    }
    else if (lockText.startsWith("\"") && lockText.endsWith("\"")) {
      issues.add(new ThreadLockIssue(
              "SynchronizedString",
              "Synchronizing on String literal can cause global contention",
              lockExpr,
              javaFile,
              getLineNumber(lockExpr),
              ThreadLockIssue.Severity.ERROR
      ));
    }
    else if (lockText.equals("getClass()") || lockText.endsWith(".class")) {
      issues.add(new ThreadLockIssue(
              "SynchronizedClass",
              "Synchronizing on Class object is usually not recommended",
              lockExpr,
              javaFile,
              getLineNumber(lockExpr),
              ThreadLockIssue.Severity.WARNING
      ));
    }
  }

  private int getLineNumber(PsiElement element) {
    return ReadAction.compute(() -> {
      PsiFile file = element.getContainingFile();
      if (file == null) return -1;
      com.intellij.openapi.editor.Document doc = com.intellij.openapi.fileEditor.FileDocumentManager
              .getInstance().getDocument(file.getVirtualFile());
      if (doc == null) return -1;
      return doc.getLineNumber(element.getTextRange().getStartOffset());
    });
  }

  private List<PsiJavaFile> collectJavaFiles(PsiDirectory directory) {
    List<PsiJavaFile> files = new ArrayList<>();
    collectJavaFilesRecursive(directory, files);
    return files;
  }

  private void collectJavaFilesRecursive(PsiDirectory dir, List<PsiJavaFile> files) {
    for (PsiFile file : dir.getFiles()) {
      if (file instanceof PsiJavaFile) {
        files.add((PsiJavaFile) file);
      }
    }
    for (PsiDirectory subDir : dir.getSubdirectories()) {
      collectJavaFilesRecursive(subDir, files);
    }
  }
}
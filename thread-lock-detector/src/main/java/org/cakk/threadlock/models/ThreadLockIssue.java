package org.cakk.threadlock.models;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;

public class ThreadLockIssue {

  private final String type;
  private final String description;
  private final PsiElement element;
  private final PsiJavaFile javaFile;
  private final int lineNumber;
  private final Severity severity;

  public enum Severity {
    ERROR, WARNING, WEAK_WARNING, INFO
  }

  public ThreadLockIssue(String type, String description, PsiElement element,
                         PsiJavaFile javaFile, int lineNumber, Severity severity) {
    this.type = type;
    this.description = description;
    this.element = element;
    this.javaFile = javaFile;
    this.lineNumber = lineNumber;
    this.severity = severity;
  }

  // Existing getters
  public String getType() { return type; }
  public String getDescription() { return description; }
  public PsiElement getElement() { return element; }
  public PsiJavaFile getJavaFile() { return javaFile; }
  public int getLineNumber() { return lineNumber; }
  public Severity getSeverity() { return severity; }

  // ✅ Added for compatibility with UI
  public PsiJavaFile getFile() {
    return javaFile;
  }

  public String getMessage() {
    return description;
  }
}
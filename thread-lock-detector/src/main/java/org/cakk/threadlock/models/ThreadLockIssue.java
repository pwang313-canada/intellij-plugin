package org.cakk.threadlock.models;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ThreadLockIssue {
  public enum Severity {
    ERROR, WARNING, WEAK_WARNING, INFO
  }

  // Common fields
  private final String type;
  private final String description;
  private final Severity severity;

  // Static analysis fields
  @Nullable
  private final PsiElement element;
  @Nullable
  private final PsiJavaFile javaFile;
  private final int lineNumber;

  // Runtime fields (optional)
  @Nullable
  private final String runtimeDetails;  // e.g., stack trace

  /**
   * Constructor for static analysis issues (with PSI element).
   */
  public ThreadLockIssue(@NotNull String type,
                         @NotNull String description,
                         @Nullable PsiElement element,
                         @Nullable PsiJavaFile javaFile,
                         int lineNumber,
                         @NotNull Severity severity) {
    this.type = type;
    this.description = description;
    this.element = element;
    this.javaFile = javaFile;
    this.lineNumber = lineNumber;
    this.severity = severity;
    this.runtimeDetails = null;
  }

  /**
   * Constructor for runtime deadlocks (without PSI).
   */
  public ThreadLockIssue(@NotNull String type,
                         @NotNull String description,
                         @NotNull String runtimeDetails,
                         @NotNull Severity severity) {
    this.type = type;
    this.description = description;
    this.element = null;
    this.javaFile = null;
    this.lineNumber = -1;
    this.severity = severity;
    this.runtimeDetails = runtimeDetails;
  }

  // Getters
  public String getType() { return type; }
  public String getDescription() { return description; }
  @Nullable public PsiElement getElement() { return element; }
  @Nullable public PsiJavaFile getJavaFile() { return javaFile; }
  public int getLineNumber() { return lineNumber; }
  public Severity getSeverity() { return severity; }
  @Nullable public String getRuntimeDetails() { return runtimeDetails; }

  // Convenience for UI compatibility (used by table model)
  @Nullable
  public PsiJavaFile getFile() {
    return javaFile;
  }

  public String getMessage() {
    return description;
  }
}
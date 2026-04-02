package org.cakk.propertytool.analysis;

import com.intellij.openapi.vfs.VirtualFile;

public class AnalysisResult {
  public enum Severity { INFO, WARNING, ERROR }

  private final VirtualFile file;
  private final int lineNumber;
  private final String message;
  private final Severity severity;

  public AnalysisResult(VirtualFile file, int lineNumber, String message, Severity severity) {
    this.file = file;
    this.lineNumber = lineNumber;
    this.message = message;
    this.severity = severity;
  }

  public VirtualFile getFile() { return file; }
  public int getLineNumber() { return lineNumber; }
  public String getMessage() { return message; }
  public Severity getSeverity() { return severity; }
}
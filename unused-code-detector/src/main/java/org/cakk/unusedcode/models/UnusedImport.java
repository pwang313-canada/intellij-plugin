// src/main/java/org/cakk/unusedcode/models/UnusedImport.java
package org.cakk.unusedcode.models;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;

public class UnusedImport {
  private final String importText;
  private final PsiImportStatement importStatement;
  private final PsiFile containingFile;
  private final String filePath;
  private final int lineNumber;  // Add line number

  public UnusedImport(String importText, PsiImportStatement importStatement, PsiFile containingFile, int lineNumber) {
    this.importText = importText;
    this.importStatement = importStatement;
    this.containingFile = containingFile;
    this.lineNumber = lineNumber;
    this.filePath = containingFile.getVirtualFile() != null ?
            containingFile.getVirtualFile().getPath() : "";
  }

  public String getImportText() { return importText; }
  public PsiImportStatement getImportStatement() { return importStatement; }
  public PsiFile getContainingFile() { return containingFile; }
  public String getFilePath() { return filePath; }
  public int getLineNumber() { return lineNumber; }  // Add getter
}
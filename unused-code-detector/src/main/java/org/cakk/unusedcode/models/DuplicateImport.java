// src/main/java/org/cakk/unusedcode/models/DuplicateImport.java
package org.cakk.unusedcode.models;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;

import java.util.List;

public class DuplicateImport {
  private final String importText;
  private final List<PsiImportStatement> importStatements;
  private final PsiFile containingFile;
  private final List<Integer> lineNumbers;
  private final String filePath;

  public DuplicateImport(String importText, List<PsiImportStatement> importStatements,
                         PsiFile containingFile, List<Integer> lineNumbers) {
    this.importText = importText;
    this.importStatements = importStatements;
    this.containingFile = containingFile;
    this.lineNumbers = lineNumbers;
    this.filePath = containingFile.getVirtualFile() != null ?
            containingFile.getVirtualFile().getPath() : "";
  }

  public String getImportText() { return importText; }
  public List<PsiImportStatement> getImportStatements() { return importStatements; }
  public PsiFile getContainingFile() { return containingFile; }
  public List<Integer> getLineNumbers() { return lineNumbers; }
  public String getFilePath() { return filePath; }

  public String getDuplicateInfo() {
    StringBuilder sb = new StringBuilder();
    sb.append(importText).append(" (duplicates at lines: ");
    for (int i = 0; i < lineNumbers.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(lineNumbers.get(i) + 1);
    }
    sb.append(")");
    return sb.toString();
  }

  public int getDuplicateCount() {
    return importStatements.size();
  }
}
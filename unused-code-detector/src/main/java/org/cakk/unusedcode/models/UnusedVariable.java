// src/main/java/org/cakk/unusedcode/models/UnusedVariable.java
package org.cakk.unusedcode.models;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiVariable;

public class UnusedVariable {

  private final String name;
  private final String className;
  private final PsiVariable variable;
  private final PsiFile containingFile;
  private final String filePath;
  private final int lineNumber;

  public UnusedVariable(String name,
                        String className,
                        PsiVariable variable,
                        PsiFile containingFile,
                        int lineNumber) {

    this.name = name;
    this.className = className;
    this.variable = variable;
    this.containingFile = containingFile;
    this.lineNumber = lineNumber;
    this.filePath = containingFile.getVirtualFile() != null ?
            containingFile.getVirtualFile().getPath() : "";
  }

  // ==================== GETTERS ====================

  public String getName() {
    return name;
  }

  public String getClassName() {
    return className;
  }

  public PsiVariable getVariable() {
    return variable;
  }

  public PsiFile getContainingFile() {
    return containingFile;
  }

  public String getFilePath() {
    return filePath;
  }

  public int getLineNumber() {
    return lineNumber;
  }
}
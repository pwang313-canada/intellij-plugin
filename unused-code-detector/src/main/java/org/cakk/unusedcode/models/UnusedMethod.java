// src/main/java/org/cakk/unusedcode/models/UnusedMethod.java
package org.cakk.unusedcode.models;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;

public class UnusedMethod {
  private final String methodName;
  private final String containingClass;
  private final PsiMethod psiMethod;
  private final PsiFile containingFile;
  private final String filePath;

  public UnusedMethod(String methodName, String containingClass, PsiMethod psiMethod, PsiFile containingFile) {
    this.methodName = methodName;
    this.containingClass = containingClass;
    this.psiMethod = psiMethod;
    this.containingFile = containingFile;
    this.filePath = containingFile.getVirtualFile() != null ?
            containingFile.getVirtualFile().getPath() : "";
  }

  public String getMethodName() { return methodName; }
  public String getContainingClass() { return containingClass; }
  public PsiMethod getPsiMethod() { return psiMethod; }
  public PsiFile getContainingFile() { return containingFile; }
  public String getFilePath() { return filePath; }

  public String getFullName() {
    return containingClass + "." + methodName + "()";
  }
}
// src/main/java/org/cakk/unusedcode/models/UnusedClass.java
package org.cakk.unusedcode.models;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;

public class UnusedClass {
  private final String className;
  private final String packageName;
  private final PsiClass psiClass;
  private final PsiFile containingFile;
  private final String filePath;

  public UnusedClass(String className, String packageName, PsiClass psiClass, PsiFile containingFile) {
    this.className = className;
    this.packageName = packageName;
    this.psiClass = psiClass;
    this.containingFile = containingFile;
    this.filePath = containingFile.getVirtualFile() != null ?
            containingFile.getVirtualFile().getPath() : "";
  }

  public String getClassName() { return className; }
  public String getPackageName() { return packageName; }
  public PsiClass getPsiClass() { return psiClass; }
  public PsiFile getContainingFile() { return containingFile; }
  public String getFilePath() { return filePath; }

  public String getFullName() {
    return packageName.isEmpty() ? className : packageName + "." + className;
  }
}
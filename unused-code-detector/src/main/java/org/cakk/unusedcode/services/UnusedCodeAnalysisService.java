// src/main/java/org/cakk/unusedcode/services/UnusedCodeAnalysisService.java
package org.cakk.unusedcode.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.cakk.unusedcode.models.DuplicateImport;
import org.cakk.unusedcode.models.UnusedClass;
import org.cakk.unusedcode.models.UnusedImport;
import org.cakk.unusedcode.models.UnusedMethod;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UnusedCodeAnalysisService {

  private final Project project;

  // Caches for performance
  private final Map<PsiJavaFile, String> codeBodyCache = new ConcurrentHashMap<>();
  private final Map<PsiJavaFile, Integer> codeStartCache = new ConcurrentHashMap<>();
  private final Map<PsiJavaFile, Set<String>> usedClassNamesCache = new ConcurrentHashMap<>();

  public UnusedCodeAnalysisService(Project project) {
    this.project = project;
  }

  // ========== PUBLIC METHODS ==========


  // ========== CORE ANALYSIS METHODS ==========


  // ========== OPTIMIZED IMPORT ANALYSIS ==========

  private void analyzeImportsOptimized(PsiJavaFile javaFile,
                                       List<UnusedImport> fileImports,
                                       ProgressIndicator indicator) {

    // Get all imports using text extraction (fast)
    List<ImportInfo> imports = extractImportsFromText(javaFile);
    if (imports.isEmpty()) return;

    // Get code body and used class names once
    String codeBody = getCodeBody(javaFile);
    Set<String> usedClassNames = extractUsedClassNames(codeBody);

    // Check each import
    for (ImportInfo info : imports) {
      if (indicator.isCanceled()) return;

      if (!isImportUsedFast(info.importText, usedClassNames, codeBody)) {
        // Find the PSI import statement for this line
        PsiImportStatement importStmt = findImportStatementAtLine(javaFile, info.lineNumber);
        if (importStmt != null) {
          // DEBUG: Print found unused import
          System.out.println("Found unused import: " + info.importText +
                  " at line " + info.lineNumber +
                  " in " + javaFile.getName());

          fileImports.add(new UnusedImport(info.importText, importStmt, javaFile, info.lineNumber));
        }
      }
    }

    // DEBUG: Print total unused imports found
    System.out.println("Total unused imports found in " + javaFile.getName() + ": " + fileImports.size());
  }

  // ========== GET CODE BODY METHODS ==========

  /**
   * Get the code body (text after imports) for a Java file
   */
  private String getCodeBody(PsiJavaFile javaFile) {
    return codeBodyCache.computeIfAbsent(javaFile, file -> {
      int codeStart = findCodeStart(file);
      if (codeStart == -1) {
        codeStart = 0;
      }
      return file.getText().substring(codeStart);
    });
  }

  /**
   * Find where code starts (after package statement and imports)
   */
  private int findCodeStart(PsiJavaFile javaFile) {
    return codeStartCache.computeIfAbsent(javaFile, file -> {
      // Check imports first
      PsiImportList importList = file.getImportList();
      if (importList != null) {
        PsiImportStatement[] imports = importList.getImportStatements();
        if (imports.length > 0) {
          PsiElement lastImport = imports[imports.length - 1];
          PsiElement nextSibling = lastImport.getNextSibling();
          if (nextSibling != null) {
            return nextSibling.getTextOffset();
          }
          return lastImport.getTextRange().getEndOffset();
        }
      }

      // Check package statement
      PsiPackageStatement packageStmt = file.getPackageStatement();
      if (packageStmt != null) {
        PsiElement nextSibling = packageStmt.getNextSibling();
        if (nextSibling != null) {
          return nextSibling.getTextOffset();
        }
        return packageStmt.getTextRange().getEndOffset();
      }

      // No imports or package, start at the beginning
      return 0;
    });
  }

  // ========== IMPORT EXTRACTION ==========

  /**
   * Extract all import statements from text (fast, no PSI)
   */
  private List<ImportInfo> extractImportsFromText(PsiJavaFile javaFile) {
    List<ImportInfo> imports = new ArrayList<>();
    String text = javaFile.getText();
    String[] lines = text.split("\n");

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.startsWith("import ")) {
        String importText = extractImportText(line);
        if (importText != null && !importText.isEmpty()) {
          imports.add(new ImportInfo(importText, i));
        }
      }
    }

    return imports;
  }

  /**
   * Extract the qualified name from an import line
   */
  private String extractImportText(String importLine) {
    // Remove "import " prefix
    String afterImport = importLine.substring(6);

    // Handle static imports
    if (afterImport.startsWith("static ")) {
      afterImport = afterImport.substring(7);
    }

    // Remove trailing semicolon
    if (afterImport.endsWith(";")) {
      afterImport = afterImport.substring(0, afterImport.length() - 1);
    }

    return afterImport.trim();
  }

  // ========== CLASS NAME EXTRACTION ==========

  /**
   * Extract all used class names from code body
   */
  private Set<String> extractUsedClassNames(String codeBody) {
    Set<String> classNames = new HashSet<>();

    // Simple tokenization - much faster than regex
    StringBuilder word = new StringBuilder();
    for (int i = 0; i < codeBody.length(); i++) {
      char c = codeBody.charAt(i);
      if (Character.isJavaIdentifierPart(c)) {
        word.append(c);
      } else {
        if (word.length() > 0) {
          String w = word.toString();
          // Consider words starting with uppercase as potential class names
          if (Character.isUpperCase(w.charAt(0))) {
            classNames.add(w);
          }
          word.setLength(0);
        }
      }
    }

    return classNames;
  }

  // ========== IMPORT USAGE CHECK ==========

  /**
   * Fast check if an import is used
   */
  private boolean isImportUsedFast(String importText, Set<String> usedClassNames, String codeBody) {
    // Skip star imports
    if (importText.endsWith(".*")) {
      return true;
    }

    // Extract simple class name
    String simpleClassName = getSimpleClassName(importText);
    if (simpleClassName == null || simpleClassName.isEmpty()) {
      return true;
    }

    // Fast O(1) lookup
    if (usedClassNames.contains(simpleClassName)) {
      return true;
    }

    // Check for fully qualified name usage
    return codeBody.contains(importText);
  }

  /**
   * Get simple class name from fully qualified import
   */
  private String getSimpleClassName(String importText) {
    int lastDot = importText.lastIndexOf('.');
    if (lastDot >= 0 && !importText.endsWith(".*")) {
      return importText.substring(lastDot + 1);
    }
    return importText;
  }

  // ========== PSI HELPERS ==========

  /**
   * Find import statement by line number
   */
  private PsiImportStatement findImportStatementAtLine(PsiJavaFile javaFile, int lineNumber) {
    PsiImportList importList = javaFile.getImportList();
    if (importList == null) return null;

    // Get document to convert offsets to line numbers
    VirtualFile virtualFile = javaFile.getVirtualFile();
    if (virtualFile == null) return null;

    Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
    if (document == null) return null;

    for (PsiImportStatement importStmt : importList.getImportStatements()) {
      int offset = importStmt.getTextRange().getStartOffset();
      int line = document.getLineNumber(offset);
      if (line == lineNumber) {
        return importStmt;
      }
    }

    return null;
  }

  // ========== CACHE MANAGEMENT ==========

  public void clearCaches() {
    codeBodyCache.clear();
    codeStartCache.clear();
    usedClassNamesCache.clear();
  }

  // ========== HELPER METHODS FOR CLASS/METHOD ANALYSIS ==========

  private boolean isClassUsed(PsiClass psiClass) {
    // Skip if it's a test class
    if (psiClass.getName() != null && psiClass.getName().contains("Test")) {
      return true;
    }

    // Check if class is a main class
    if (psiClass.getName() != null && psiClass.getName().equals("Main")) {
      return true;
    }

    // TODO: Add more sophisticated class usage detection
    return true;
  }

  private boolean isMethodUsed(PsiMethod method) {
    String methodName = method.getName();

    // Getters and setters might be used by frameworks
    if (methodName.startsWith("get") || methodName.startsWith("set") || methodName.startsWith("is")) {
      return true;
    }

    // Constructors are always used when class is instantiated
    if (method.isConstructor()) {
      return true;
    }

    // TODO: Add more sophisticated method usage detection
    return true;
  }

  private boolean isMethodCheckable(PsiMethod method) {
    // Skip constructors
    if (method.isConstructor()) {
      return false;
    }

    // Skip abstract methods
    if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
      return false;
    }

    // Skip native methods
    if (method.hasModifierProperty(PsiModifier.NATIVE)) {
      return false;
    }

    // Skip methods with @Override annotation
    PsiModifierList modifierList = method.getModifierList();
    if (modifierList != null) {
      if (modifierList.findAnnotation("java.lang.Override") != null ||
              modifierList.findAnnotation("Override") != null) {
        return false;
      }
    }

    // Only analyze private methods or static private methods
    if (method.hasModifierProperty(PsiModifier.PUBLIC)) {
      return false;
    }

    if (method.hasModifierProperty(PsiModifier.PROTECTED)) {
      return false;
    }

    return true;
  }

  // ========== INNER CLASSES ==========

  private static class ImportInfo {
    final String importText;
    final int lineNumber;

    ImportInfo(String importText, int lineNumber) {
      this.importText = importText;
      this.lineNumber = lineNumber;
    }
  }
// Add to UnusedCodeAnalysisService.java

  /**
   * Analyze a single file for duplicate imports
   */
// In UnusedCodeAnalysisService.java - add debug in analyzeDuplicateImports
  private void analyzeDuplicateImports(PsiJavaFile javaFile,
                                       List<DuplicateImport> fileDuplicates,
                                       ProgressIndicator indicator) {

    if (indicator.isCanceled()) return;

    // Get all imports using text extraction
    List<ImportInfo> imports = extractImportsFromText(javaFile);
    if (imports.isEmpty()) return;

    // Group imports by import text
    Map<String, List<ImportInfo>> importGroups = new HashMap<>();
    for (ImportInfo info : imports) {
      importGroups.computeIfAbsent(info.importText, k -> new ArrayList<>()).add(info);
    }

    // Find duplicates (more than one occurrence)
    for (Map.Entry<String, List<ImportInfo>> entry : importGroups.entrySet()) {
      if (indicator.isCanceled()) return;

      List<ImportInfo> duplicateInfos = entry.getValue();
      if (duplicateInfos.size() > 1) {
        String importText = entry.getKey();
        List<Integer> lineNumbers = new ArrayList<>();
        List<PsiImportStatement> importStatements = new ArrayList<>();

        for (ImportInfo info : duplicateInfos) {
          lineNumbers.add(info.lineNumber);
          PsiImportStatement importStmt = findImportStatementAtLine(javaFile, info.lineNumber);
          if (importStmt != null) {
            importStatements.add(importStmt);
          }
        }

        DuplicateImport duplicate = new DuplicateImport(importText, importStatements, javaFile, lineNumbers);
        fileDuplicates.add(duplicate);

        // DEBUG: Print found duplicate
        System.out.println("Found duplicate import: " + importText + " at lines " + lineNumbers);
        System.out.println("  Added to fileDuplicates, size now: " + fileDuplicates.size());
      }
    }

    // DEBUG: Print total duplicates found
    System.out.println("Total duplicates found in " + javaFile.getName() + ": " + fileDuplicates.size());
  }

  // Update analyzeSingleFile to include duplicate detection
  private void analyzeSingleFile(PsiJavaFile javaFile,
                                 List<UnusedClass> fileClasses,
                                 List<UnusedMethod> fileMethods,
                                 List<UnusedImport> fileImports,
                                 List<DuplicateImport> fileDuplicates,
                                 ProgressIndicator indicator) {

    if (indicator.isCanceled()) return;

    // Analyze imports (optimized)
    analyzeImportsOptimized(javaFile, fileImports, indicator);

    // Analyze duplicate imports
    analyzeDuplicateImports(javaFile, fileDuplicates, indicator);

    if (indicator.isCanceled()) return;

    // Analyze classes and methods
    PsiClass[] classes = javaFile.getClasses();
    for (PsiClass psiClass : classes) {
      if (indicator.isCanceled()) return;

      // Check class usage
      if (!isClassUsed(psiClass)) {
        fileClasses.add(new UnusedClass(
                psiClass.getName(),
                psiClass.getContainingClass() != null ?
                        psiClass.getContainingClass().getQualifiedName() : "",
                psiClass,
                javaFile
        ));
      }

      // Analyze methods
      for (PsiMethod method : psiClass.getMethods()) {
        if (indicator.isCanceled()) return;

        if (isMethodCheckable(method) && !isMethodUsed(method)) {
          fileMethods.add(new UnusedMethod(
                  method.getName(),
                  psiClass.getName(),
                  method,
                  javaFile
          ));
        }
      }
    }
  }

  // Update analyzeFiles method to include duplicates
  public void analyzeFiles(List<PsiJavaFile> javaFiles, AnalysisCallback callback) {
    new Task.Backgroundable(project, "Analyzing files for unused code", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          List<UnusedClass> allClasses = new ArrayList<>();
          List<UnusedMethod> allMethods = new ArrayList<>();
          List<UnusedImport> allImports = new ArrayList<>();
          List<DuplicateImport> allDuplicates = new ArrayList<>();

          int total = javaFiles.size();
          int processed = 0;

          for (PsiJavaFile javaFile : javaFiles) {
            if (indicator.isCanceled()) break;

            processed++;
            indicator.setFraction((double) processed / total);
            indicator.setText("Analyzing: " + javaFile.getName());

            List<UnusedClass> fileClasses = new ArrayList<>();
            List<UnusedMethod> fileMethods = new ArrayList<>();
            List<UnusedImport> fileImports = new ArrayList<>();
            List<DuplicateImport> fileDuplicates = new ArrayList<>();

            analyzeSingleFile(javaFile, fileClasses, fileMethods, fileImports, fileDuplicates, indicator);

            allClasses.addAll(fileClasses);
            allMethods.addAll(fileMethods);
            allImports.addAll(fileImports);
            allDuplicates.addAll(fileDuplicates);
          }

          ApplicationManager.getApplication().invokeLater(() ->
                  callback.onComplete(allClasses, allMethods, allImports, allDuplicates)
          );

        } catch (Exception e) {
          ApplicationManager.getApplication().invokeLater(() ->
                  callback.onError(e.getMessage())
          );
        } finally {
          clearCaches();
        }
      }
    }.queue();
  }

  // Update analyzeFileImportsOnly to include duplicate detection
  public void analyzeFileImportsOnly(PsiJavaFile javaFile, AnalysisCallback callback) {
    new Task.Backgroundable(project, "Analyzing imports", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          List<UnusedClass> emptyClasses = new ArrayList<>();
          List<UnusedMethod> emptyMethods = new ArrayList<>();
          List<UnusedImport> unusedImports = new ArrayList<>();
          List<DuplicateImport> duplicateImports = new ArrayList<>();

          analyzeImportsOptimized(javaFile, unusedImports, indicator);
          analyzeDuplicateImports(javaFile, duplicateImports, indicator);

          ApplicationManager.getApplication().invokeLater(() ->
                  callback.onComplete(emptyClasses, emptyMethods, unusedImports, duplicateImports)
          );

        } catch (Exception e) {
          ApplicationManager.getApplication().invokeLater(() ->
                  callback.onError(e.getMessage())
          );
        } finally {
          clearCaches();
        }
      }
    }.queue();
  }

  // Update AnalysisCallback interface
  public interface AnalysisCallback {
    void onComplete(List<UnusedClass> classes,
                    List<UnusedMethod> methods,
                    List<UnusedImport> imports,
                    List<DuplicateImport> duplicates);
    void onError(String error);
  }

}
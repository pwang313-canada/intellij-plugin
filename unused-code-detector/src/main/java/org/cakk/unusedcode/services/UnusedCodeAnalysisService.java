// src/main/java/org/cakk/unusedcode/services/UnusedCodeAnalysisService.java
package org.cakk.unusedcode.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Query;
import org.cakk.unusedcode.models.DuplicateImport;
import org.cakk.unusedcode.models.UnusedClass;
import org.cakk.unusedcode.models.UnusedImport;
import org.cakk.unusedcode.models.UnusedMethod;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.intellij.debugger.impl.DebuggerUtilsEx.getLineNumber;

public class UnusedCodeAnalysisService {

  private final Project project;

  // Caches for performance
  private final Map<PsiJavaFile, String> codeBodyCache = new ConcurrentHashMap<>();
  private final Map<PsiJavaFile, Integer> codeStartCache = new ConcurrentHashMap<>();
  private final Map<PsiJavaFile, Set<String>> usedClassNamesCache = new ConcurrentHashMap<>();
  private final Map<PsiClass, Boolean> classUsageCache = new ConcurrentHashMap<>();
  private final Map<PsiMethod, Boolean> methodUsageCache = new ConcurrentHashMap<>();

  public UnusedCodeAnalysisService(Project project) {
    this.project = project;
  }

  // ========== PUBLIC METHODS ==========

  public void analyzeFiles(List<PsiJavaFile> javaFiles, AnalysisCallback callback) {
    new Task.Backgroundable(project, "Analyzing files for unused code", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          // Clear caches to ensure fresh analysis
          clearCaches();

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
          clearCaches(); // optional, but safe
        }
      }
    }.queue();
  }

  public void analyzeFileImportsOnly(PsiJavaFile javaFile, AnalysisCallback callback) {
    new Task.Backgroundable(project, "Analyzing imports", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          clearCaches();

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

  // ========== CORE ANALYSIS METHODS ==========

  private void analyzeSingleFile(PsiJavaFile javaFile,
                                 List<UnusedClass> fileClasses,
                                 List<UnusedMethod> fileMethods,
                                 List<UnusedImport> fileImports,
                                 List<DuplicateImport> fileDuplicates,
                                 ProgressIndicator indicator) {

    if (indicator.isCanceled()) return;

    // Analyze imports and duplicates (these already use ReadAction internally)
    analyzeImportsOptimized(javaFile, fileImports, indicator);
    analyzeDuplicateImports(javaFile, fileDuplicates, indicator);

    if (indicator.isCanceled()) return;

    // All PSI reads below must be under a read action
    ReadAction.run(() -> {
      PsiClass[] classes = javaFile.getClasses();
      for (PsiClass psiClass : classes) {
        if (indicator.isCanceled()) return;

        // Check class usage (already uses ReadAction internally)
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
            int lineNumber = getLineNumber(method);
            fileMethods.add(new UnusedMethod(
                    method.getName(),
                    psiClass.getName(),
                    method,
                    javaFile,
                    lineNumber
            ));
          }
        }
      }
    });
  }
  // ========== OPTIMIZED IMPORT ANALYSIS ==========

  private void analyzeImportsOptimized(PsiJavaFile javaFile,
                                       List<UnusedImport> fileImports,
                                       ProgressIndicator indicator) {

    List<ImportInfo> imports = extractImportsFromText(javaFile);
    if (imports.isEmpty()) return;

    String codeBody = getCodeBody(javaFile);
    Set<String> usedClassNames = extractUsedClassNames(codeBody);

    for (ImportInfo info : imports) {
      if (indicator.isCanceled()) return;

      if (!isImportUsedFast(info.importText, usedClassNames, codeBody)) {
        PsiImportStatement importStmt = findImportStatementAtLine(javaFile, info.lineNumber);
        if (importStmt != null) {
          fileImports.add(new UnusedImport(info.importText, importStmt, javaFile, info.lineNumber));
        }
      }
    }
  }

  private void analyzeDuplicateImports(PsiJavaFile javaFile,
                                       List<DuplicateImport> fileDuplicates,
                                       ProgressIndicator indicator) {

    List<ImportInfo> imports = extractImportsFromText(javaFile);
    if (imports.isEmpty()) return;

    Map<String, List<ImportInfo>> importGroups = new HashMap<>();
    for (ImportInfo info : imports) {
      importGroups.computeIfAbsent(info.importText, k -> new ArrayList<>()).add(info);
    }

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

        fileDuplicates.add(new DuplicateImport(importText, importStatements, javaFile, lineNumbers));
      }
    }
  }

  // ========== GET CODE BODY METHODS ==========

  private String getCodeBody(PsiJavaFile javaFile) {
    return codeBodyCache.computeIfAbsent(javaFile, file ->
            ReadAction.compute(() -> {
              int codeStart = findCodeStart(file);
              if (codeStart == -1) codeStart = 0;
              return file.getText().substring(codeStart);
            })
    );
  }

  private int findCodeStart(PsiJavaFile javaFile) {
    return codeStartCache.computeIfAbsent(javaFile, file ->
            ReadAction.compute(() -> {
              PsiImportList importList = file.getImportList();
              if (importList != null) {
                PsiImportStatement[] imports = importList.getImportStatements();
                if (imports.length > 0) {
                  PsiElement lastImport = imports[imports.length - 1];
                  PsiElement nextSibling = lastImport.getNextSibling();
                  if (nextSibling != null) return nextSibling.getTextOffset();
                  return lastImport.getTextRange().getEndOffset();
                }
              }

              PsiPackageStatement packageStmt = file.getPackageStatement();
              if (packageStmt != null) {
                PsiElement nextSibling = packageStmt.getNextSibling();
                if (nextSibling != null) return nextSibling.getTextOffset();
                return packageStmt.getTextRange().getEndOffset();
              }

              return 0;
            })
    );
  }

  // ========== IMPORT EXTRACTION ==========

  private List<ImportInfo> extractImportsFromText(PsiJavaFile javaFile) {
    return ReadAction.compute(() -> {
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
    });
  }

  private String extractImportText(String importLine) {
    String afterImport = importLine.substring(6);
    if (afterImport.startsWith("static ")) afterImport = afterImport.substring(7);
    if (afterImport.endsWith(";")) afterImport = afterImport.substring(0, afterImport.length() - 1);
    return afterImport.trim();
  }

  // ========== CLASS NAME EXTRACTION ==========

  private Set<String> extractUsedClassNames(String codeBody) {
    Set<String> classNames = new HashSet<>();
    StringBuilder word = new StringBuilder();
    for (int i = 0; i < codeBody.length(); i++) {
      char c = codeBody.charAt(i);
      if (Character.isJavaIdentifierPart(c)) {
        word.append(c);
      } else {
        if (word.length() > 0) {
          String w = word.toString();
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

  private boolean isImportUsedFast(String importText, Set<String> usedClassNames, String codeBody) {
    if (importText.endsWith(".*")) return true;
    String simpleClassName = getSimpleClassName(importText);
    if (simpleClassName == null || simpleClassName.isEmpty()) return true;
    if (usedClassNames.contains(simpleClassName)) return true;
    return codeBody.contains(importText);
  }

  private String getSimpleClassName(String importText) {
    int lastDot = importText.lastIndexOf('.');
    if (lastDot >= 0 && !importText.endsWith(".*")) {
      return importText.substring(lastDot + 1);
    }
    return importText;
  }

  // ========== PSI HELPERS ==========

  private PsiImportStatement findImportStatementAtLine(PsiJavaFile javaFile, int lineNumber) {
    return ReadAction.compute(() -> {
      PsiImportList importList = javaFile.getImportList();
      if (importList == null) return null;

      VirtualFile virtualFile = javaFile.getVirtualFile();
      if (virtualFile == null) return null;

      Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
      if (document == null) return null;

      for (PsiImportStatement importStmt : importList.getImportStatements()) {
        int offset = importStmt.getTextRange().getStartOffset();
        int line = document.getLineNumber(offset);
        if (line == lineNumber) return importStmt;
      }
      return null;
    });
  }

  // ========== UNUSED CLASS DETECTION ==========

  private boolean isClassUsed(PsiClass psiClass) {
    String qName = psiClass.getQualifiedName();
    if (qName == null) return true;

    // Skip Java standard library
    if (qName.startsWith("java.") || qName.startsWith("javax.") || qName.startsWith("jdk.")) {
      return true;
    }

    // Check whitelist
    String simpleName = psiClass.getName();
    if (simpleName != null && getWhitelist().isClassWhitelisted(simpleName)) {
      return true;
    }

    return classUsageCache.computeIfAbsent(psiClass, cls ->
            ReadAction.compute(() -> {
              GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
              Query<PsiReference> query = ReferencesSearch.search(cls, scope);
              for (PsiReference ref : query) {
                PsiElement element = ref.getElement();
                if (element != null && !PsiTreeUtil.isAncestor(cls, element, true)) {
                  return true; // external reference found
                }
              }
              return false; // no external reference → unused
            })
    );
  }

  // ========== UNUSED METHOD DETECTION ==========

  private boolean isMethodUsed(PsiMethod method) {
    String className = method.getContainingClass().getQualifiedName();
    String methodName = method.getName();

    // Whitelist check (uses simple class name, as per your requirement)
    if (className != null && getWhitelist().isMethodWhitelisted(className, methodName)) {
      return true;
    }

    // Cached reference search
    return methodUsageCache.computeIfAbsent(method, m -> ReadAction.compute(() -> {
      GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
      Query<PsiReference> query = ReferencesSearch.search(m, scope);
      for (PsiReference ref : query) {
        PsiElement element = ref.getElement();
        // If the reference is not inside the method itself, it's a usage
        if (element != null && !PsiTreeUtil.isAncestor(m, element, true)) {
          return true;
        }
      }
      return false;
    }));
  }

  private boolean isMethodCheckable(PsiMethod method) {
    if (method.isConstructor()) return false;
    if (method.hasModifierProperty(PsiModifier.ABSTRACT)) return false;
    if (method.hasModifierProperty(PsiModifier.NATIVE)) return false;

    PsiModifierList modifierList = method.getModifierList();
    if (modifierList != null) {
      if (modifierList.findAnnotation("java.lang.Override") != null ||
              modifierList.findAnnotation("Override") != null) {
        return false;
      }
    }

    // Only consider private methods (public/protected may be used externally)
    return method.hasModifierProperty(PsiModifier.PRIVATE);
  }

  // ========== WHITELIST HELPERS ==========

  private WhitelistService getWhitelist() {
    return WhitelistService.getInstance();
  }

  // ========== CACHE MANAGEMENT ==========

  public void clearCaches() {
    codeBodyCache.clear();
    codeStartCache.clear();
    usedClassNamesCache.clear();
    classUsageCache.clear();
    methodUsageCache.clear();
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

  public interface AnalysisCallback {
    void onComplete(List<UnusedClass> classes,
                    List<UnusedMethod> methods,
                    List<UnusedImport> imports,
                    List<DuplicateImport> duplicates);
    void onError(String error);
  }

  private int getLineNumber(PsiElement element) {
    return ReadAction.compute(() -> {
      VirtualFile vf = element.getContainingFile().getVirtualFile();
      if (vf == null) return -1;
      Document doc = FileDocumentManager.getInstance().getDocument(vf);
      if (doc == null) return -1;
      return doc.getLineNumber(element.getTextRange().getStartOffset());
    });
  }
}
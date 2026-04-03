package org.cakk.propertytool.analysis;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.cakk.propertytool.actions.AnalyzePropertiesAction;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PropertiesAnalyzer {
  private final Project project;
  private static final Logger LOG = Logger.getInstance(PropertiesAnalyzer.class);

  public PropertiesAnalyzer(Project project) {
    this.project = project;
  }

  public List<AnalysisResult> analyze(List<VirtualFile> files, boolean checkCrossReferences) {
    List<AnalysisResult> results = new ArrayList<>();
    for (VirtualFile file : files) {
      String content;
      try {
        content = new String(file.contentsToByteArray());
      } catch (Exception e) {
        results.add(new AnalysisResult(file, 0, "Error reading file: " + e.getMessage(), AnalysisResult.Severity.ERROR));
        continue;
      }

      boolean isYaml = file.getName().endsWith(".yml") || file.getName().endsWith(".yaml");
      if (isYaml) {
        analyzeYaml(file, content, results, checkCrossReferences);
      } else {
        analyzeProperties(file, content, results, checkCrossReferences);
      }
    }
    return results;
  }

  private void analyzeProperties(VirtualFile file, String content, List<AnalysisResult> results, boolean checkCrossReferences) {
    List<PropertyEntry> entries = parseProperties(content);

    // 1. Duplicate keys
    Map<String, List<PropertyEntry>> keyToEntries = new HashMap<>();
    for (PropertyEntry entry : entries) {
      keyToEntries.computeIfAbsent(entry.key, k -> new ArrayList<>()).add(entry);
    }
    for (Map.Entry<String, List<PropertyEntry>> e : keyToEntries.entrySet()) {
      if (e.getValue().size() > 1) {
        for (PropertyEntry entry : e.getValue()) {
          results.add(new AnalysisResult(file, entry.lineNumber,
                  "Duplicate key: '" + entry.key + "' (also defined at line " +
                          e.getValue().get(0).lineNumber + ")", AnalysisResult.Severity.WARNING));
        }
      }
    }

    // 2. Illegal characters / formatting
    String[] lines = content.split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
      if (!line.contains("=") && !line.contains(":")) {
        results.add(new AnalysisResult(file, i + 1, "Missing key-value separator", AnalysisResult.Severity.ERROR));
        continue;
      }
      String key = line.split("[=:]", 2)[0].trim();
      if (key.contains(" ") || key.contains("=") || key.contains(":")) {
        results.add(new AnalysisResult(file, i + 1, "Key contains illegal characters: " + key, AnalysisResult.Severity.ERROR));
      }
    }

    // Cross‑reference checks only if requested
    if (checkCrossReferences) {
      Set<String> allKeys = new HashSet<>();
      for (PropertyEntry entry : entries) allKeys.add(entry.key);

      Set<String> referencedKeys = findReferencedKeys();

      // If no references found (maybe no Java files or visitor failed), skip
      if (referencedKeys.isEmpty()) {
        results.add(new AnalysisResult(file, -1,
                "Cross‑reference check skipped – no Java references found",
                AnalysisResult.Severity.INFO));
      } else {
        // 3. Missing keys (referenced but not in properties)
        for (String refKey : referencedKeys) {
          if (!allKeys.contains(refKey)) {
            results.add(new AnalysisResult(file, -1,
                    "Missing key referenced in code: " + refKey,
                    AnalysisResult.Severity.ERROR));
          }
        }


        Set<String> normalizedReferencedKeys = new HashSet<>();
        for (String refKey : referencedKeys) {
          String normalized = refKey.trim();
          // Remove ${...} wrapper if present (e.g., ${my.key} -> my.key)
          if (normalized.startsWith("${") && normalized.endsWith("}")) {
            normalized = normalized.substring(2, normalized.length() - 1);
          }
          normalizedReferencedKeys.add(normalized);
        }

        LOG.info("=== DEBUG ===");
        LOG.info("Entry keys: " + entries.stream().map(e -> "'" + e.key + "'").collect(Collectors.joining(", ")));
        LOG.info("Referenced keys (raw): " + referencedKeys);
        LOG.info("Normalized referenced: " + normalizedReferencedKeys);
        // 4. Unused keys (present but not referenced)
        for (PropertyEntry entry : entries) {
          String normalizedEntryKey = entry.key.trim();
          if (!normalizedReferencedKeys.contains(normalizedEntryKey)) {
            results.add(new AnalysisResult(file, entry.lineNumber,
                    "Unused key: " + entry.key,
                    AnalysisResult.Severity.WARNING));
          }
        }
      }
    }
  }

  private void analyzeYaml(VirtualFile file, String content, List<AnalysisResult> results, boolean checkCrossReferences) {
    List<PropertyEntry> entries = parseYaml(content);
  }

  private List<PropertyEntry> parseProperties(String content) {
    List<PropertyEntry> entries = new ArrayList<>();
    String[] lines = content.split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (!line.contains("=") && !line.contains(":")) continue;
      String[] parts = line.split("[=:]", 2);
      String key = parts[0].trim();
      String value = parts.length > 1 ? parts[1].trim() : "";
      entries.add(new PropertyEntry(key, value, i + 1));
    }
    return entries;
  }

  private List<PropertyEntry> parseYaml(String content) {
    List<PropertyEntry> entries = new ArrayList<>();
    String[] lines = content.split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (line.contains(":")) {
        String[] parts = line.split(":", 2);
        String key = parts[0].trim();
        String value = parts.length > 1 ? parts[1].trim() : "";
        entries.add(new PropertyEntry(key, value, i + 1));
      }
    }
    return entries;
  }

  /**
   * Finds all property keys referenced in Java/Kotlin source files.
   * Uses only PsiRecursiveElementVisitor – no JavaRecursiveElementVisitor.
   */
  private Set<String> findReferencedKeys() {
    return ReadAction.compute(() -> {
      Set<String> keys = new HashSet<>();
      PsiManager psiManager = PsiManager.getInstance(project);
      GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

      Collection<VirtualFile> javaFiles = FilenameIndex.getAllFilesByExt(project, "java", scope);
      // also collect Kotlin files if needed

      LOG.info("javaFiles="+javaFiles);
      for (VirtualFile vf : javaFiles) {
        PsiFile psiFile = psiManager.findFile(vf);
        if (psiFile == null) continue;

        psiFile.accept(new JavaRecursiveElementVisitor() {
          @Override
          public void visitLiteralExpression(PsiLiteralExpression expr) {
            if (expr.getValue() instanceof String) {
              String str = (String) expr.getValue();

              // Always extract placeholders (e.g., ${my.key}) from any string
              extractPlaceholders(str, keys);

              // Then add the whole string if it looks like a simple property key
              // (alphanumeric, dot, underscore, hyphen – no spaces, no special chars)
              if (str.matches("[a-zA-Z0-9._-]+")) {
                keys.add(str);
              }
            }
            super.visitLiteralExpression(expr);
          }

          @Override
          public void visitMethodCallExpression(PsiMethodCallExpression call) {
            String methodName = call.getMethodExpression().getReferenceName();
            if ("getProperty".equals(methodName) || "get".equals(methodName)) {
              PsiExpression[] args = call.getArgumentList().getExpressions();
              if (args.length > 0 && args[0] instanceof PsiLiteralExpression) {
                Object val = ((PsiLiteralExpression) args[0]).getValue();
                if (val instanceof String) keys.add((String) val);
              }
            }
            super.visitMethodCallExpression(call);
          }

          private void extractPlaceholders(String str, Set<String> keys) {
            Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(str);
            while (m.find()) keys.add(m.group(1));
          }
        });
      }
      LOG.info("keys=" + keys);
      return keys;
    });
  }

  private static class PropertyEntry {
    String key;
    String value;
    int lineNumber;

    PropertyEntry(String key, String value, int lineNumber) {
      this.key = key;
      this.value = value;
      this.lineNumber = lineNumber;
    }
  }
}
package org.cakk.propertytool.analysis;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.*;

public class PropertiesAnalyzer {

  private final Project project;

  public PropertiesAnalyzer(Project project) {
    this.project = project;
  }

  public List<AnalysisResult> analyze(List<VirtualFile> files) {
    List<AnalysisResult> results = new ArrayList<>();
    for (VirtualFile vf : files) {
      // Read file content
      String content;
      try {
        content = new String(vf.contentsToByteArray());
      } catch (Exception e) {
        results.add(new AnalysisResult(vf, 0, "Error reading file: " + e.getMessage(), AnalysisResult.Severity.ERROR));
        continue;
      }

      // Detect file type
      boolean isYaml = vf.getName().endsWith(".yml") || vf.getName().endsWith(".yaml");

      if (isYaml) {
        // For simplicity, we treat YAML as a flat key-value map (no nested structures)
        // In a real plugin, you'd parse YAML properly.
        analyzeYaml(vf, content, results);
      } else {
        analyzeProperties(vf, content, results);
      }
    }
    return results;
  }

  private void analyzeProperties(VirtualFile file, String content, List<AnalysisResult> results) {
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

    // 2. Illegal characters/formatting
    String[] lines = content.split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
      if (!line.contains("=") && !line.contains(":")) {
        results.add(new AnalysisResult(file, i+1, "Missing key-value separator", AnalysisResult.Severity.ERROR));
        continue;
      }
      // Check key for illegal characters (space, colon, equals sign not escaped)
      String key = line.split("[=:]", 2)[0].trim();
      if (key.contains(" ") || key.contains("=") || key.contains(":")) {
        results.add(new AnalysisResult(file, i+1, "Key contains illegal characters: " + key, AnalysisResult.Severity.ERROR));
      }
    }

    // 3. Missing keys (referenced in code)
    Set<String> allKeys = new HashSet<>();
    for (PropertyEntry entry : entries) {
      allKeys.add(entry.key);
    }
    Set<String> referencedKeys = findReferencedKeys();
    for (String refKey : referencedKeys) {
      if (!allKeys.contains(refKey)) {
        results.add(new AnalysisResult(file, -1, "Missing key referenced in code: " + refKey, AnalysisResult.Severity.ERROR));
      }
    }

    // 4. Unused keys
    for (PropertyEntry entry : entries) {
      if (!referencedKeys.contains(entry.key)) {
        results.add(new AnalysisResult(file, entry.lineNumber, "Unused key: " + entry.key, AnalysisResult.Severity.WARNING));
      }
    }
  }

  private void analyzeYaml(VirtualFile file, String content, List<AnalysisResult> results) {
    // Simplified: treat as key: value lines
    List<PropertyEntry> entries = parseYaml(content);
    // Similar checks as above...
    // For brevity, reuse the same logic with adapted parser.
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
      entries.add(new PropertyEntry(key, value, i+1));
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
        entries.add(new PropertyEntry(key, value, i+1));
      }
    }
    return entries;
  }

  // This method should scan the entire project for references to property keys.
  private Set<String> findReferencedKeys() {
    Set<String> keys = new HashSet<>();
    // Example patterns:
    // - Spring: @Value("${key}")
    // - Java: properties.getProperty("key")
    // - Kotlin: System.getProperty("key")
    // This is a simplified stub.
    // In a real plugin, you'd use PSI to search for string literals that match property key patterns.
    return keys;
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
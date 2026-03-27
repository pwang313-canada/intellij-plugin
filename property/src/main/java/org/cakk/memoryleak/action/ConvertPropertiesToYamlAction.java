package org.cakk.memoryleak.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConvertPropertiesToYamlAction extends AnAction implements DumbAware {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(AnActionEvent e) {
    VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    boolean visible = false;

    if (files != null) {
      for (VirtualFile f : files) {
        if (f.getName().endsWith(".properties") || f.isDirectory()) {
          visible = true;
          break;
        }
      }
    }

    e.getPresentation().setEnabledAndVisible(visible);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {

    Project project = e.getProject();
    VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

    if (project == null || selectedFiles == null || selectedFiles.length == 0) {
      return;
    }

    List<VirtualFile> propertiesFiles = collectPropertiesFiles(selectedFiles);

    if (propertiesFiles.isEmpty()) {
      Messages.showInfoMessage(project, "No .properties files found.", "Nothing to Convert");
      return;
    }

    int confirm = Messages.showYesNoDialog(
            project,
            "Found " + propertiesFiles.size() + " properties files. Convert to YAML?",
            "Confirm Conversion",
            Messages.getQuestionIcon()
    );

    if (confirm != Messages.YES) return;

    int success = 0;
    int skipped = 0;

    for (VirtualFile file : propertiesFiles) {

      try {

        VirtualFile parent = file.getParent();
        String yamlName = file.getNameWithoutExtension() + ".yml";

        VirtualFile existing = parent.findChild(yamlName);

        if (existing != null) {

          int overwrite = Messages.showYesNoCancelDialog(
                  project,
                  yamlName + " already exists.\nOverwrite?",
                  "File Exists",
                  "Overwrite",
                  "Skip",
                  "Cancel",
                  Messages.getWarningIcon()
          );

          if (overwrite == Messages.CANCEL) break;
          if (overwrite == Messages.NO) {
            skipped++;
            continue;
          }
        }

        Map<String, Object> yamlMap = loadProperties(file);

        String yamlText = generateYaml(yamlMap);

        WriteCommandAction.runWriteCommandAction(project, () -> {

          try {

            VirtualFile yamlFile = parent.findChild(yamlName);

            if (yamlFile == null) {
              yamlFile = parent.createChildData(this, yamlName);
            }

            yamlFile.setBinaryContent(yamlText.getBytes(StandardCharsets.UTF_8));

          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }

        });

        success++;

      } catch (Exception ex) {

        Messages.showErrorDialog(
                project,
                "Failed to convert: " + file.getName() + "\n" + ex.getMessage(),
                "Conversion Error"
        );
      }
    }

    Messages.showInfoMessage(
            project,
            "Converted: " + success + "\nSkipped: " + skipped,
            "Conversion Complete"
    );
  }

  List<VirtualFile> collectPropertiesFiles(VirtualFile[] files) {

    List<VirtualFile> result = new ArrayList<>();

    for (VirtualFile file : files) {

      if (file.isDirectory()) {
        collectRecursive(file, result);
      } else if (file.getName().endsWith(".properties")) {
        result.add(file);
      }
    }

    return result;
  }

  private void collectRecursive(VirtualFile dir, List<VirtualFile> result) {

    for (VirtualFile child : dir.getChildren()) {

      if (child.isDirectory()) {
        collectRecursive(child, result);
      } else if (child.getName().endsWith(".properties")) {
        result.add(child);
      }
    }
  }

  Map<String, Object> loadProperties(VirtualFile file) throws Exception {

    Properties props = new Properties();

    try (InputStream is = file.getInputStream()) {
      props.load(is);
    }

    Map<String, Object> yamlMap = new LinkedHashMap<>();

    for (String key : props.stringPropertyNames()) {
      insert(yamlMap, key, props.getProperty(key));
    }

    return yamlMap;
  }

  String generateYaml(Map<String, Object> map) {

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);

    Yaml yaml = new Yaml(options);

    return yaml.dump(map);
  }

  @SuppressWarnings("unchecked")
  private static void insert(Map<String, Object> map, String key, String value) {

    String[] parts = key.split("\\.");
    Map<String, Object> current = map;

    for (int i = 0; i < parts.length; i++) {

      String part = parts[i];

      boolean last = i == parts.length - 1;

      if (part.contains("[") && part.endsWith("]")) {

        String name = part.substring(0, part.indexOf('['));
        int index = Integer.parseInt(part.substring(part.indexOf('[') + 1, part.indexOf(']')));

        Object obj = current.get(name);

        List<Object> list;

        if (obj instanceof List) {
          list = (List<Object>) obj;
        } else {
          list = new ArrayList<>();
          current.put(name, list);
        }

        while (list.size() <= index) list.add(null);

        if (last) {

          list.set(index, value);

        } else {

          Object nested = list.get(index);

          if (!(nested instanceof Map)) {
            nested = new LinkedHashMap<String, Object>();
            list.set(index, nested);
          }

          current = (Map<String, Object>) nested;
        }

      } else {

        if (last) {

          current.put(part, value);

        } else {

          Object next = current.get(part);

          if (!(next instanceof Map)) {
            next = new LinkedHashMap<String, Object>();
            current.put(part, next);
          }

          current = (Map<String, Object>) next;
        }
      }
    }
  }
}
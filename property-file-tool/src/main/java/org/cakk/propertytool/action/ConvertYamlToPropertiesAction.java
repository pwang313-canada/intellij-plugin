package org.cakk.propertytool.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConvertYamlToPropertiesAction extends AnAction {

  private static final Logger LOG = Logger.getInstance(ConvertYamlToPropertiesAction.class);
  private static final List<String> YAML_EXTENSIONS = Arrays.asList(".yml", ".yaml");

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

    boolean visible = files != null && files.length > 0 &&
            Arrays.stream(files).allMatch(this::isYamlFile);

    e.getPresentation().setEnabledAndVisible(visible);

    if (files != null && files.length > 1) {
      e.getPresentation().setText("Convert " + files.length + " YAML Files to Properties");
    } else {
      e.getPresentation().setText("Convert YAML to Properties");
    }
  }

  private boolean isYamlFile(VirtualFile file) {
    if (file == null || file.isDirectory()) return false;
    String name = file.getName().toLowerCase();
    return YAML_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {

    Project project = e.getProject();
    VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

    if (project == null || files == null || files.length == 0) {
      return;
    }

    List<VirtualFile> yamlFiles = new ArrayList<>();
    for (VirtualFile f : files) {
      if (isYamlFile(f)) yamlFiles.add(f);
    }

    if (yamlFiles.isEmpty()) {
      Messages.showWarningDialog(project, "No YAML files selected", "Warning");
      return;
    }

    int result = Messages.showYesNoDialog(project,
            "Convert " + yamlFiles.size() + " YAML file(s) to Properties?",
            "Confirm Conversion",
            "Convert",
            "Cancel",
            Messages.getQuestionIcon());

    if (result != Messages.YES) return;

    convertFilesWithProgress(project, yamlFiles);
  }

  private void convertFilesWithProgress(Project project, List<VirtualFile> yamlFiles) {

    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Converting YAML to Properties", true) {

      @Override
      public void run(@NotNull ProgressIndicator indicator) {

        List<ConversionResult> results = new ArrayList<>();

        int total = yamlFiles.size();

        for (int i = 0; i < total; i++) {

          VirtualFile file = yamlFiles.get(i);

          indicator.setText("Converting: " + file.getName());
          indicator.setFraction((double) i / total);

          try {

            String content = convertYaml(file);

            writePropertiesFile(project, file, content);

            results.add(new ConversionResult(file.getName(), true, null));

          } catch (Exception ex) {

            LOG.warn("Failed to convert " + file.getName(), ex);

            results.add(new ConversionResult(file.getName(), false, ex.getMessage()));
          }
        }

        ApplicationManager.getApplication()
                .invokeLater(() -> showConversionResults(project, results));
      }
    });
  }

  private String convertYaml(VirtualFile file) throws IOException {

    try (InputStream is = file.getInputStream()) {

      Yaml yaml = new Yaml();
      Object yamlData = yaml.load(is);

      if (yamlData == null) {
        throw new IOException("Empty YAML file");
      }

      Properties properties = new Properties();

      if (yamlData instanceof Map) {
        flattenYamlStructure("", (Map<String, Object>) yamlData, properties);
      } else {
        properties.setProperty("root", yamlData.toString());
      }

      return buildPropertiesContent(file, properties);
    }
  }

  private void writePropertiesFile(Project project, VirtualFile yamlFile, String content) {

    WriteCommandAction.runWriteCommandAction(project, () -> {

      try {

        VirtualFile parent = yamlFile.getParent();

        if (parent == null) return;

        String newName = yamlFile.getNameWithoutExtension() + ".properties";

        VirtualFile newFile = parent.findChild(newName);

        if (newFile == null) {
          newFile = parent.createChildData(this, newName);
        }

        newFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));

      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }

    });
  }

  private String buildPropertiesContent(VirtualFile file, Properties properties) {

    StringBuilder content = new StringBuilder();

    content.append("# Converted from YAML: ").append(file.getName()).append("\n");
    content.append("# Conversion date: ").append(new Date()).append("\n");
    content.append("# Total properties: ").append(properties.size()).append("\n\n");

    properties.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey().toString()))
            .forEach(entry ->
                    content.append(entry.getKey())
                            .append("=")
                            .append(entry.getValue())
                            .append("\n")
            );

    return content.toString();
  }

  @SuppressWarnings("unchecked")
  private void flattenYamlStructure(String prefix, Map<String, Object> data, Properties props) {

    for (Map.Entry<String, Object> entry : data.entrySet()) {

      String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      Object value = entry.getValue();

      if (value instanceof Map) {
        flattenYamlStructure(key, (Map<String, Object>) value, props);
      }
      else if (value instanceof List) {
        flattenYamlList(key, (List<?>) value, props);
      }
      else {
        props.setProperty(key, value == null ? "" : value.toString());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void flattenYamlList(String prefix, List<?> list, Properties props) {

    for (int i = 0; i < list.size(); i++) {

      Object item = list.get(i);

      String key = prefix + "[" + i + "]";

      if (item instanceof Map) {
        flattenYamlStructure(key, (Map<String, Object>) item, props);
      }
      else if (item instanceof List) {
        flattenYamlList(key, (List<?>) item, props);
      }
      else {
        props.setProperty(key, item == null ? "" : item.toString());
      }
    }
  }

  private void showConversionResults(Project project, List<ConversionResult> results) {

    long success = results.stream().filter(r -> r.success).count();
    long fail = results.size() - success;

    if (fail == 0) {

      Messages.showInfoMessage(project,
              "Successfully converted " + success + " file(s)",
              "Conversion Complete");

    } else {

      StringBuilder msg = new StringBuilder();

      msg.append("Converted: ").append(success)
              .append(", Failed: ").append(fail).append("\n\n");

      msg.append("Failed files:\n");

      results.stream()
              .filter(r -> !r.success)
              .forEach(r ->
                      msg.append(" - ")
                              .append(r.fileName)
                              .append(": ")
                              .append(r.error)
                              .append("\n")
              );

      Messages.showErrorDialog(project, msg.toString(), "Conversion Completed with Errors");
    }
  }

  private record ConversionResult(String fileName, boolean success, String error) {

  }
}
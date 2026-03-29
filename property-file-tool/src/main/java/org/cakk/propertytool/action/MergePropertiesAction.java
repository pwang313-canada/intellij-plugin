package org.cakk.propertytool.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MergePropertiesAction extends AnAction {

  private static final String APPLICATION_PROPERTIES = "application.properties";

  private static final List<String> EXCLUDED_FILES = Arrays.asList(
          "application.properties",
          "application.yml",
          "application.yaml",
          "bootstrap.properties",
          "bootstrap.yml",
          "bootstrap.yaml"
  );

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);

    boolean visible = file != null &&
            file.isDirectory() &&
            "resources".equals(file.getName());

    e.getPresentation().setEnabledAndVisible(visible);

    if (visible) {
      e.getPresentation().setText("Merge and Clean Properties Files");
    }
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {

    Project project = e.getProject();
    VirtualFile resourcesFolder = e.getData(CommonDataKeys.VIRTUAL_FILE);

    if (project == null || resourcesFolder == null) return;

    List<VirtualFile> propertiesFiles = findPropertiesFiles(resourcesFolder);

    List<VirtualFile> filesToProcess = new ArrayList<>();
    for (VirtualFile f : propertiesFiles) {
      if (!EXCLUDED_FILES.contains(f.getName())) {
        filesToProcess.add(f);
      }
    }

    if (filesToProcess.isEmpty()) {
      Messages.showWarningDialog(project, "No additional .properties files found.", "Nothing to Do");
      return;
    }

    int confirm = Messages.showYesNoDialog(
            project,
            "Found " + filesToProcess.size() + " properties files.\nContinue?",
            "Confirm Merge",
            Messages.getQuestionIcon()
    );

    if (confirm != Messages.YES) return;

    try {

      Map<String, Map<String, String>> fileToProps = new LinkedHashMap<>();
      Map<String, Map<String, String>> keyToValues = new LinkedHashMap<>();

      for (VirtualFile file : filesToProcess) {

        Properties props = loadProperties(file);

        Map<String, String> map = new LinkedHashMap<>();

        for (String key : props.stringPropertyNames()) {

          String value = props.getProperty(key);
          map.put(key, value);

          keyToValues.computeIfAbsent(key, k -> new LinkedHashMap<>())
                  .put(file.getName(), value);
        }

        fileToProps.put(file.getName(), map);
      }

      int totalFiles = filesToProcess.size();

      Map<String, String> commonProps = new LinkedHashMap<>();

      for (Map.Entry<String, Map<String, String>> entry : keyToValues.entrySet()) {

        if (entry.getValue().size() == totalFiles) {

          Set<String> values = new HashSet<>(entry.getValue().values());

          if (values.size() == 1) {
            commonProps.put(entry.getKey(), values.iterator().next());
          }
        }
      }

      // ✅ Write everything in ONE WriteAction
      WriteCommandAction.runWriteCommandAction(project, () -> {

        try {

          VirtualFile appFile = getOrCreateApplicationProperties(resourcesFolder);
          Properties merged = loadProperties(appFile);

          commonProps.forEach(merged::setProperty);

          writePropertiesVfs(appFile, merged);

          for (VirtualFile file : filesToProcess) {

            Map<String, String> original = fileToProps.get(file.getName());

            Properties cleaned = new Properties();

            for (Map.Entry<String, String> entry : original.entrySet()) {
              if (!commonProps.containsKey(entry.getKey())) {
                cleaned.setProperty(entry.getKey(), entry.getValue());
              }
            }

            writePropertiesVfs(file, cleaned);
          }

        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      });

      resourcesFolder.refresh(false, true);

      Messages.showInfoMessage(project,
              "Merge completed.\nCommon keys moved: " + commonProps.size(),
              "Success");

    } catch (Exception ex) {
      Messages.showErrorDialog(project, "Merge failed: " + ex.getMessage(), "Error");
    }
  }

  private List<VirtualFile> findPropertiesFiles(VirtualFile folder) {

    List<VirtualFile> result = new ArrayList<>();

    VirtualFile[] children = folder.getChildren();
    if (children == null) return result;

    for (VirtualFile child : children) {
      if (child.isDirectory()) {
        result.addAll(findPropertiesFiles(child));
      } else if (child.getName().endsWith(".properties")) {
        result.add(child);
      }
    }

    return result;
  }

  private Properties loadProperties(VirtualFile file) throws Exception {

    Properties props = new Properties();

    try (InputStream is = file.getInputStream();
         InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
      props.load(reader);
    }

    return props;
  }

  private void writePropertiesVfs(VirtualFile file, Properties props) throws Exception {

    List<String> keys = new ArrayList<>(props.stringPropertyNames());
    Collections.sort(keys);

    StringBuilder content = new StringBuilder();

    for (String key : keys) {
      String value = props.getProperty(key);
      content.append(key).append("=").append(value).append("\n");
    }

    file.setBinaryContent(content.toString().getBytes(StandardCharsets.UTF_8));
  }

  private VirtualFile getOrCreateApplicationProperties(VirtualFile folder) throws Exception {

    VirtualFile file = folder.findChild(APPLICATION_PROPERTIES);

    if (file == null) {
      file = folder.createChildData(this, APPLICATION_PROPERTIES);
    }

    return file;
  }
}
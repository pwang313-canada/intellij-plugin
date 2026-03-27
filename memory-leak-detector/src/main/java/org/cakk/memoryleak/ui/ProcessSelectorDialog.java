package org.cakk.memoryleak.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ProcessSelectorDialog extends DialogWrapper {

  private final JBList<RemoteMemoryMonitorService.JavaProcess> processList;
  private String selectedProcessId;

  public ProcessSelectorDialog(Project project, RemoteMemoryMonitorService remoteService) {
    super(project);

    List<RemoteMemoryMonitorService.JavaProcess> processes = remoteService.listJavaProcesses();
    processList = new JBList<>(processes.toArray(new RemoteMemoryMonitorService.JavaProcess[0]));
    processList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    processList.setCellRenderer(new ProcessListCellRenderer());

    setTitle("Select Java Process to Monitor");
    setSize(800, 500);
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel panel = new JPanel(new BorderLayout(10, 10));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    JLabel label = new JLabel("Select the Java application to monitor for memory leaks:");
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    panel.add(label, BorderLayout.NORTH);

    JBScrollPane scrollPane = new JBScrollPane(processList);
    panel.add(scrollPane, BorderLayout.CENTER);

    JTextArea infoArea = new JTextArea();
    infoArea.setText(
            "💡 Tips for remote monitoring:\n" +
                    "• For best results, run your application with JMX enabled:\n" +
                    "  java -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9020 -jar your-app.jar\n" +
                    "• Processes marked with ✅ JMX Ready are ready to monitor\n" +
                    "• Processes marked with ❌ JMX Not Enabled need to be restarted with JMX enabled\n" +
                    "• You can also attach to processes without JMX, but GC events won't be captured"
    );
    infoArea.setEditable(false);
    infoArea.setBackground(panel.getBackground());
    infoArea.setForeground(Color.GRAY);
    infoArea.setFont(infoArea.getFont().deriveFont(11f));
    infoArea.setBorder(BorderFactory.createEmptyBorder(10, 5, 5, 5));
    panel.add(infoArea, BorderLayout.SOUTH);

    return panel;
  }

  @Override
  protected void doOKAction() {
    RemoteMemoryMonitorService.JavaProcess selected = processList.getSelectedValue();
    if (selected != null) {
      selectedProcessId = selected.pid();
    }
    super.doOKAction();
  }

  public String getSelectedProcessId() {
    return selectedProcessId;
  }

  private static class ProcessListCellRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value,
                                                  int index, boolean isSelected,
                                                  boolean cellHasFocus) {
      super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

      if (value instanceof RemoteMemoryMonitorService.JavaProcess) {
        RemoteMemoryMonitorService.JavaProcess process =
                (RemoteMemoryMonitorService.JavaProcess) value;

        String status = process.jmxEnabled() ? "✅" : "❌";
        String text = String.format("%s PID: %s - %s (%s)",
                status, process.pid(), process.mainClass(), process.displayName());
        setText(text);

        if (process.jmxEnabled()) {
          setIcon(UIManager.getIcon("OptionPane.informationIcon"));
        } else {
          setIcon(UIManager.getIcon("OptionPane.warningIcon"));
        }
      }

      return this;
    }
  }
}
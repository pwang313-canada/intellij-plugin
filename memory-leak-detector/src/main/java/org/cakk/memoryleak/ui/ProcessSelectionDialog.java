// src/main/java/org/cakk/memoryleak/ui/ProcessSelectionDialog.java
package org.cakk.memoryleak.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ProcessSelectionDialog extends DialogWrapper {

  private final Project project;
  private final RemoteMemoryMonitorService remoteService;
  private JBList<RemoteMemoryMonitorService.JavaProcess> processList;
  private DefaultListModel<RemoteMemoryMonitorService.JavaProcess> listModel;
  private RemoteMemoryMonitorService.JavaProcess selectedProcess;

  public ProcessSelectionDialog(Project project, RemoteMemoryMonitorService remoteService) {
    super(project);
    this.project = project;
    this.remoteService = remoteService;
    setTitle("Select Java Process to Monitor");
    setSize(600, 500);
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(10));

    // Instruction label
    JLabel label = new JLabel("Select a Java process to monitor for memory leaks:");
    label.setBorder(JBUI.Borders.empty(0, 0, 10, 0));
    panel.add(label, BorderLayout.NORTH);

    // Process list
    listModel = new DefaultListModel<>();
    processList = new JBList<>(listModel);
    processList.setCellRenderer(new ProcessListRenderer());
    processList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    JScrollPane scrollPane = new JBScrollPane(processList);
    scrollPane.setPreferredSize(new Dimension(550, 350));
    panel.add(scrollPane, BorderLayout.CENTER);

    // Button panel
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    JButton refreshButton = new JButton("Refresh");
    refreshButton.addActionListener(e -> refreshProcessList());
    buttonPanel.add(refreshButton);

    JButton helpButton = new JButton("Help");
    helpButton.addActionListener(e -> showHelp());
    buttonPanel.add(helpButton);

    panel.add(buttonPanel, BorderLayout.SOUTH);

    refreshProcessList();
    return panel;
  }

  private void refreshProcessList() {
    listModel.clear();
    List<RemoteMemoryMonitorService.JavaProcess> processes = remoteService.listJavaProcesses();
    for (RemoteMemoryMonitorService.JavaProcess process : processes) {
      listModel.addElement(process);
    }

    if (listModel.isEmpty()) {
      listModel.addElement(null); // Add placeholder
    }
  }

  private void showHelp() {
    String helpMessage =
            "To monitor a Java application for memory leaks:\n\n" +
                    "1. Run your application with JMX enabled:\n" +
                    "   java -Dcom.sun.management.jmxremote \\\n" +
                    "        -Dcom.sun.management.jmxremote.port=9010 \\\n" +
                    "        -Dcom.sun.management.jmxremote.authenticate=false \\\n" +
                    "        -Dcom.sun.management.jmxremote.ssl=false \\\n" +
                    "        -jar your-app.jar\n\n" +
                    "2. In IntelliJ, go to Run → Edit Configurations\n" +
                    "3. Add the VM options above to your application\n" +
                    "4. Run your application\n" +
                    "5. Click 'Connect to Remote Process' and select your app\n\n" +
                    "Processes with ✅ JMX Ready can be connected immediately.\n" +
                    "Processes with ❌ JMX Not Enabled need to be restarted with JMX enabled.";

    JOptionPane.showMessageDialog(
            getContentPane(),
            helpMessage,
            "How to Enable JMX",
            JOptionPane.INFORMATION_MESSAGE
    );
  }

  @Override
  protected void doOKAction() {
    selectedProcess = processList.getSelectedValue();
    if (selectedProcess == null) {
      JOptionPane.showMessageDialog(
              getContentPane(),
              "Please select a process to monitor",
              "No Selection",
              JOptionPane.WARNING_MESSAGE
      );
      return;
    }

    try {
      remoteService.connectToProcess(selectedProcess.pid());
      super.doOKAction();
    } catch (Exception e) {
      JOptionPane.showMessageDialog(
              getContentPane(),
              "Failed to connect: " + e.getMessage(),
              "Connection Error",
              JOptionPane.ERROR_MESSAGE
      );
    }
  }

  public RemoteMemoryMonitorService.JavaProcess getSelectedProcess() {
    return selectedProcess;
  }

  private static class ProcessListRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value,
                                                  int index, boolean isSelected,
                                                  boolean cellHasFocus) {
      super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
      if (value instanceof RemoteMemoryMonitorService.JavaProcess process) {
        setText(process.toString());
        if (process.jmxEnabled()) {
          setForeground(new Color(80, 200, 80));
        } else {
          setForeground(Color.GRAY);
        }
      } else if (value == null) {
        setText("No Java processes found. Make sure your application is running.");
        setForeground(Color.GRAY);
      }
      return this;
    }
  }
}
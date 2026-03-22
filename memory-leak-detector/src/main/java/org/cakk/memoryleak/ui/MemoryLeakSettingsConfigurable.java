// src/main/java/org/cakk/memoryleak/ui/MemoryLeakSettingsConfigurable.java
package org.cakk.memoryleak.ui;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.cakk.memoryleak.settings.MemoryLeakSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class MemoryLeakSettingsConfigurable implements Configurable {

  private final Project project;
  private JPanel mainPanel;

  // Threshold components
  private JBTextField rapidGrowthThresholdField;
  private JBTextField warningGrowthThresholdField;
  private JBTextField highRiskGrowthThresholdField;
  private JBTextField lowGcEfficiencyField;
  private JBTextField criticalGcEfficiencyField;

  // Monitoring components
  private JBCheckBox autoStartCheckbox;
  private JBTextField monitoringIntervalField;
  private JBTextField baselineSettleField;

  // Alert components
  private JBCheckBox showGcDialogCheckbox;
  private JBCheckBox showNotificationsCheckbox;
  private JBCheckBox autoRunGcCheckbox;
  private JBCheckBox logToConsoleCheckbox;

  // UI components
  private JBCheckBox showToolWindowCheckbox;
  private JBCheckBox showMemoryChartCheckbox;

  public MemoryLeakSettingsConfigurable(@NotNull Project project) {
    this.project = project;
  }

  @Nls(capitalization = Nls.Capitalization.Title)
  @Override
  public String getDisplayName() {
    return "Memory Leak Detector";
  }

  @Override
  public @Nullable JComponent createComponent() {
    // Initialize all components
    initComponents();

    // Create main panel with tabs
    mainPanel = new JPanel(new BorderLayout());
    mainPanel.setBorder(JBUI.Borders.empty(10));

    JTabbedPane tabbedPane = new JTabbedPane();
    tabbedPane.addTab("Detection Thresholds", createThresholdsPanel());
    tabbedPane.addTab("Monitoring", createMonitoringPanel());
    tabbedPane.addTab("Alerts", createAlertsPanel());
    tabbedPane.addTab("UI Settings", createUIPanel());

    mainPanel.add(tabbedPane, BorderLayout.CENTER);

    return mainPanel;
  }

  private void initComponents() {
    MemoryLeakSettings settings = MemoryLeakSettings.getInstance(project);

    // Threshold fields
    rapidGrowthThresholdField = new JBTextField(String.valueOf(settings.getRapidGrowthThreshold()));
    warningGrowthThresholdField = new JBTextField(String.valueOf(settings.getWarningGrowthThreshold()));
    highRiskGrowthThresholdField = new JBTextField(String.valueOf(settings.getHighRiskGrowthThreshold()));
    lowGcEfficiencyField = new JBTextField(String.valueOf(settings.getLowGcEfficiencyThreshold()));
    criticalGcEfficiencyField = new JBTextField(String.valueOf(settings.getCriticalGcEfficiencyThreshold()));

    // Monitoring components
    autoStartCheckbox = new JBCheckBox("Auto-start monitoring on project open", settings.isAutoStartMonitoring());
    monitoringIntervalField = new JBTextField(String.valueOf(settings.getMonitoringIntervalSeconds()));
    baselineSettleField = new JBTextField(String.valueOf(settings.getBaselineSettleTimeSeconds()));

    // Alert components
    showGcDialogCheckbox = new JBCheckBox("Show GC confirmation dialog", settings.isShowGcConfirmationDialog());
    showNotificationsCheckbox = new JBCheckBox("Show leak notifications", settings.isShowLeakNotifications());
    autoRunGcCheckbox = new JBCheckBox("Auto-run GC on high risk (no confirmation)", settings.isAutoRunGcOnHighRisk());
    logToConsoleCheckbox = new JBCheckBox("Log to console", settings.isLogToConsole());

    // UI components
    showToolWindowCheckbox = new JBCheckBox("Show tool window on startup", settings.isShowToolWindowOnStartup());
    showMemoryChartCheckbox = new JBCheckBox("Show memory chart", settings.isShowMemoryChart());
  }

  private JPanel createThresholdsPanel() {
    JPanel panel = FormBuilder.createFormBuilder()
            .setFormLeftIndent(10)
            .addLabeledComponent(
                    new JBLabel("Rapid Growth Threshold (%)"),
                    rapidGrowthThresholdField
            )
            .addTooltip("Alert when old gen growth exceeds this percentage per interval (default: 10%)")
            .addLabeledComponent(
                    new JBLabel("Warning Growth Threshold (%)"),
                    warningGrowthThresholdField
            )
            .addTooltip("Warn when old gen growth exceeds this percentage from baseline (default: 20%)")
            .addLabeledComponent(
                    new JBLabel("High Risk Growth Threshold (%)"),
                    highRiskGrowthThresholdField
            )
            .addTooltip("High risk alert when old gen growth exceeds this percentage (default: 50%)")
            .addLabeledComponent(
                    new JBLabel("Low GC Efficiency Threshold (%)"),
                    lowGcEfficiencyField
            )
            .addTooltip("Consider GC inefficient below this percentage (default: 20%)")
            .addLabeledComponent(
                    new JBLabel("Critical GC Efficiency Threshold (%)"),
                    criticalGcEfficiencyField
            )
            .addTooltip("Critical GC inefficiency below this percentage (default: 10%)")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    // Add explanation
    JTextArea explanation = new JTextArea(
            "Thresholds determine when the detector triggers alerts.\n\n" +
                    "• Rapid Growth: Immediate alert for sudden memory increase\n" +
                    "• Warning: Steady growth pattern with poor GC efficiency\n" +
                    "• High Risk: Severe growth with very poor GC efficiency\n" +
                    "• GC Efficiency: How well GC reclaims old generation memory"
    );
    explanation.setEditable(false);
    explanation.setOpaque(false);
    explanation.setWrapStyleWord(true);
    explanation.setLineWrap(true);
    explanation.setFont(explanation.getFont().deriveFont(Font.PLAIN, 11f));
    explanation.setBorder(JBUI.Borders.empty(10, 0, 0, 0));

    JPanel result = new JPanel(new BorderLayout());
    result.add(panel, BorderLayout.CENTER);
    result.add(explanation, BorderLayout.SOUTH);

    return result;
  }

  private JPanel createMonitoringPanel() {
    JPanel panel = FormBuilder.createFormBuilder()
            .setFormLeftIndent(10)
            .addComponent(autoStartCheckbox)
            .addLabeledComponent(
                    new JBLabel("Monitoring Interval (seconds)"),
                    monitoringIntervalField
            )
            .addTooltip("How often to sample memory usage (1-60 seconds)")
            .addLabeledComponent(
                    new JBLabel("Baseline Settle Time (seconds)"),
                    baselineSettleField
            )
            .addTooltip("Time to wait after GC before establishing baseline")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    // Add explanation
    JTextArea explanation = new JTextArea(
            "Monitoring settings control how the detector samples memory.\n\n" +
                    "• Monitoring Interval: Lower values give more precise data but increase overhead\n" +
                    "• Baseline Settle Time: Time to let the JVM stabilize after GC\n\n" +
                    "Note: Frequent monitoring has minimal performance impact (<1% CPU)"
    );
    explanation.setEditable(false);
    explanation.setOpaque(false);
    explanation.setWrapStyleWord(true);
    explanation.setLineWrap(true);
    explanation.setFont(explanation.getFont().deriveFont(Font.PLAIN, 11f));
    explanation.setBorder(JBUI.Borders.empty(10, 0, 0, 0));

    JPanel result = new JPanel(new BorderLayout());
    result.add(panel, BorderLayout.CENTER);
    result.add(explanation, BorderLayout.SOUTH);

    return result;
  }

  private JPanel createAlertsPanel() {
    JPanel panel = FormBuilder.createFormBuilder()
            .setFormLeftIndent(10)
            .addComponent(showGcDialogCheckbox)
            .addComponent(showNotificationsCheckbox)
            .addComponent(autoRunGcCheckbox)
            .addComponent(logToConsoleCheckbox)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    // Add warning for auto-run GC
    JTextArea warning = new JTextArea(
            "⚠️ Warning: Auto-run GC will force garbage collection without confirmation.\n" +
                    "This may cause temporary application pauses. Use with caution in production."
    );
    warning.setEditable(false);
    warning.setOpaque(false);
    warning.setWrapStyleWord(true);
    warning.setLineWrap(true);
    warning.setForeground(new Color(255, 100, 0));
    warning.setFont(warning.getFont().deriveFont(Font.PLAIN, 11f));
    warning.setBorder(JBUI.Borders.empty(10, 0, 0, 0));

    JPanel result = new JPanel(new BorderLayout());
    result.add(panel, BorderLayout.CENTER);
    result.add(warning, BorderLayout.SOUTH);

    return result;
  }

  private JPanel createUIPanel() {
    JPanel panel = FormBuilder.createFormBuilder()
            .setFormLeftIndent(10)
            .addComponent(showToolWindowCheckbox)
            .addComponent(showMemoryChartCheckbox)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    // Add explanation
    JTextArea explanation = new JTextArea(
            "UI settings control how the plugin displays information.\n\n" +
                    "• Show tool window on startup: Opens Memory Monitor automatically\n" +
                    "• Show memory chart: Display or hide the chart in tool window"
    );
    explanation.setEditable(false);
    explanation.setOpaque(false);
    explanation.setWrapStyleWord(true);
    explanation.setLineWrap(true);
    explanation.setFont(explanation.getFont().deriveFont(Font.PLAIN, 11f));
    explanation.setBorder(JBUI.Borders.empty(10, 0, 0, 0));

    JPanel result = new JPanel(new BorderLayout());
    result.add(panel, BorderLayout.CENTER);
    result.add(explanation, BorderLayout.SOUTH);

    return result;
  }

  @Override
  public boolean isModified() {
    MemoryLeakSettings settings = MemoryLeakSettings.getInstance(project);

    try {
      return Double.parseDouble(rapidGrowthThresholdField.getText().trim()) != settings.getRapidGrowthThreshold() ||
              Double.parseDouble(warningGrowthThresholdField.getText().trim()) != settings.getWarningGrowthThreshold() ||
              Double.parseDouble(highRiskGrowthThresholdField.getText().trim()) != settings.getHighRiskGrowthThreshold() ||
              Double.parseDouble(lowGcEfficiencyField.getText().trim()) != settings.getLowGcEfficiencyThreshold() ||
              Double.parseDouble(criticalGcEfficiencyField.getText().trim()) != settings.getCriticalGcEfficiencyThreshold() ||
              autoStartCheckbox.isSelected() != settings.isAutoStartMonitoring() ||
              Integer.parseInt(monitoringIntervalField.getText().trim()) != settings.getMonitoringIntervalSeconds() ||
              Integer.parseInt(baselineSettleField.getText().trim()) != settings.getBaselineSettleTimeSeconds() ||
              showGcDialogCheckbox.isSelected() != settings.isShowGcConfirmationDialog() ||
              showNotificationsCheckbox.isSelected() != settings.isShowLeakNotifications() ||
              autoRunGcCheckbox.isSelected() != settings.isAutoRunGcOnHighRisk() ||
              logToConsoleCheckbox.isSelected() != settings.isLogToConsole() ||
              showToolWindowCheckbox.isSelected() != settings.isShowToolWindowOnStartup() ||
              showMemoryChartCheckbox.isSelected() != settings.isShowMemoryChart();
    } catch (NumberFormatException e) {
      return false;
    }
  }

  @Override
  public void apply() {
    MemoryLeakSettings settings = MemoryLeakSettings.getInstance(project);

    try {
      settings.setRapidGrowthThreshold(Double.parseDouble(rapidGrowthThresholdField.getText().trim()));
      settings.setWarningGrowthThreshold(Double.parseDouble(warningGrowthThresholdField.getText().trim()));
      settings.setHighRiskGrowthThreshold(Double.parseDouble(highRiskGrowthThresholdField.getText().trim()));
      settings.setLowGcEfficiencyThreshold(Double.parseDouble(lowGcEfficiencyField.getText().trim()));
      settings.setCriticalGcEfficiencyThreshold(Double.parseDouble(criticalGcEfficiencyField.getText().trim()));
      settings.setAutoStartMonitoring(autoStartCheckbox.isSelected());
      settings.setMonitoringIntervalSeconds(Integer.parseInt(monitoringIntervalField.getText().trim()));
      settings.setBaselineSettleTimeSeconds(Integer.parseInt(baselineSettleField.getText().trim()));
      settings.setShowGcConfirmationDialog(showGcDialogCheckbox.isSelected());
      settings.setShowLeakNotifications(showNotificationsCheckbox.isSelected());
      settings.setAutoRunGcOnHighRisk(autoRunGcCheckbox.isSelected());
      settings.setLogToConsole(logToConsoleCheckbox.isSelected());
      settings.setShowToolWindowOnStartup(showToolWindowCheckbox.isSelected());
      settings.setShowMemoryChart(showMemoryChartCheckbox.isSelected());
    } catch (NumberFormatException e) {
      // Invalid input - ignore
    }
  }

  @Override
  public void reset() {
    MemoryLeakSettings settings = MemoryLeakSettings.getInstance(project);

    rapidGrowthThresholdField.setText(String.valueOf(settings.getRapidGrowthThreshold()));
    warningGrowthThresholdField.setText(String.valueOf(settings.getWarningGrowthThreshold()));
    highRiskGrowthThresholdField.setText(String.valueOf(settings.getHighRiskGrowthThreshold()));
    lowGcEfficiencyField.setText(String.valueOf(settings.getLowGcEfficiencyThreshold()));
    criticalGcEfficiencyField.setText(String.valueOf(settings.getCriticalGcEfficiencyThreshold()));
    autoStartCheckbox.setSelected(settings.isAutoStartMonitoring());
    monitoringIntervalField.setText(String.valueOf(settings.getMonitoringIntervalSeconds()));
    baselineSettleField.setText(String.valueOf(settings.getBaselineSettleTimeSeconds()));
    showGcDialogCheckbox.setSelected(settings.isShowGcConfirmationDialog());
    showNotificationsCheckbox.setSelected(settings.isShowLeakNotifications());
    autoRunGcCheckbox.setSelected(settings.isAutoRunGcOnHighRisk());
    logToConsoleCheckbox.setSelected(settings.isLogToConsole());
    showToolWindowCheckbox.setSelected(settings.isShowToolWindowOnStartup());
    showMemoryChartCheckbox.setSelected(settings.isShowMemoryChart());
  }

  @Override
  public void disposeUIResources() {
    mainPanel = null;
    rapidGrowthThresholdField = null;
    warningGrowthThresholdField = null;
    highRiskGrowthThresholdField = null;
    lowGcEfficiencyField = null;
    criticalGcEfficiencyField = null;
    autoStartCheckbox = null;
    monitoringIntervalField = null;
    baselineSettleField = null;
    showGcDialogCheckbox = null;
    showNotificationsCheckbox = null;
    autoRunGcCheckbox = null;
    logToConsoleCheckbox = null;
    showToolWindowCheckbox = null;
    showMemoryChartCheckbox = null;
  }
}
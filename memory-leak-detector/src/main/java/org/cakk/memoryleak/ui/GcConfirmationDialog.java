package org.cakk.memoryleak.ui;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class GcConfirmationDialog extends DialogWrapper {

  private final MemoryMonitorService.RapidGrowthAlert alert;
  private final Runnable onGcConfirmed;
  private final Runnable onIgnore;

  public GcConfirmationDialog(MemoryMonitorService.RapidGrowthAlert alert,
                              Runnable onGcConfirmed,
                              Runnable onIgnore) {
    super(true);
    this.alert = alert;
    this.onGcConfirmed = onGcConfirmed;
    this.onIgnore = onIgnore;

    setTitle("Memory Leak Detection Alert");
    setOKButtonText("Run System.gc()");
    setCancelButtonText("Ignore");

    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(20));

    // Warning icon and message
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    Icon warningIcon = UIManager.getIcon("OptionPane.warningIcon");
    if (warningIcon != null) {
      topPanel.add(new JLabel(warningIcon));
    }

    JLabel titleLabel = new JLabel("Rapid Old Generation Growth Detected!");
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
    titleLabel.setForeground(new Color(255, 100, 0));
    topPanel.add(titleLabel);
    panel.add(topPanel, BorderLayout.NORTH);

    // Details panel
    JPanel detailsPanel = new JPanel(new GridBagLayout());
    detailsPanel.setBorder(JBUI.Borders.empty(15, 0));
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = JBUI.insets(5);
    gbc.anchor = GridBagConstraints.WEST;

    addDetailRow(detailsPanel, gbc, "Recent Growth Rate:",
            String.format("%.2f%% per interval", alert.growthRate()));
    addDetailRow(detailsPanel, gbc, "Old Gen Growth (from baseline):",
            String.format("%.2f%%", alert.oldGenGrowthPercent()));
    addDetailRow(detailsPanel, gbc, "GC Efficiency:",
            String.format("%.2f%%", alert.gcEfficiency()));
    addDetailRow(detailsPanel, gbc, "Current Old Gen:",
            formatBytes(alert.currentOldGen()));
    addDetailRow(detailsPanel, gbc, "Baseline Old Gen:",
            formatBytes(alert.baselineOldGen()));

    panel.add(detailsPanel, BorderLayout.CENTER);

    // Explanation
    JPanel bottomPanel = new JPanel(new BorderLayout());
    bottomPanel.setBorder(JBUI.Borders.empty(10, 0, 0, 0));

    JTextArea explanation = new JTextArea(
            "Rapid growth in old generation memory may indicate a memory leak.\n" +
                    "Running System.gc() can help verify if memory can be reclaimed.\n\n" +
                    "Note: This is a hint to the JVM and does not guarantee immediate collection."
    );
    explanation.setEditable(false);
    explanation.setOpaque(false);
    explanation.setWrapStyleWord(true);
    explanation.setLineWrap(true);
    // Use a standard font instead of JBLabel.getDefaultFont()
    explanation.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));

    bottomPanel.add(explanation, BorderLayout.CENTER);
    panel.add(bottomPanel, BorderLayout.SOUTH);

    return panel;
  }

  private void addDetailRow(JPanel panel, GridBagConstraints gbc,
                            String label, String value) {
    gbc.gridx = 0;
    gbc.weightx = 0.3;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    JLabel labelComp = new JLabel(label);
    labelComp.setFont(labelComp.getFont().deriveFont(Font.BOLD));
    panel.add(labelComp, gbc);

    gbc.gridx = 1;
    gbc.weightx = 0.7;
    JLabel valueLabel = new JLabel(value);
    valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN));
    panel.add(valueLabel, gbc);

    gbc.gridy++;
  }

  @Override
  protected void doOKAction() {
    super.doOKAction();
    if (onGcConfirmed != null) {
      onGcConfirmed.run();
    }
  }

  @Override
  public void doCancelAction() {
    super.doCancelAction();
    if (onIgnore != null) {
      onIgnore.run();
    }
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }
}
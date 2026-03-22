// src/main/java/org/cakk/memoryleak/ui/StatisticsPanel.java
package org.cakk.memoryleak.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.cakk.memoryleak.services.MemoryMonitorService;

import javax.swing.*;
import java.awt.*;

public class StatisticsPanel extends JPanel {

  private final JBLabel heapUsedLabel;
  private final JBLabel heapCommittedLabel;
  private final JBLabel heapMaxLabel;
  private final JBLabel oldGenUsedLabel;
  private final JBLabel oldGenCommittedLabel;
  private final JBLabel youngGenUsedLabel;
  private final JBLabel metaspaceUsedLabel;
  private final JBLabel gcEfficiencyLabel;
  private final JBLabel heapGrowthLabel;
  private final JBLabel oldGenGrowthLabel;

  // Growth tracking
  private long lastHeapUsed = 0;
  private long lastOldGenUsed = 0;

  public StatisticsPanel() {
    setLayout(new GridBagLayout());
    setBorder(JBUI.Borders.empty(10));

    heapUsedLabel = new JBLabel("--");
    heapCommittedLabel = new JBLabel("--");
    heapMaxLabel = new JBLabel("--");
    oldGenUsedLabel = new JBLabel("--");
    oldGenCommittedLabel = new JBLabel("--");
    youngGenUsedLabel = new JBLabel("--");
    metaspaceUsedLabel = new JBLabel("--");
    gcEfficiencyLabel = new JBLabel("--");
    heapGrowthLabel = new JBLabel("--");
    oldGenGrowthLabel = new JBLabel("--");

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.insets = JBUI.insets(2, 5, 2, 5);

    // Section: Heap Memory
    addSection("Heap Memory", gbc);
    addStat("Used:", heapUsedLabel, gbc);
    addStat("Committed:", heapCommittedLabel, gbc);
    addStat("Max:", heapMaxLabel, gbc);

    gbc.gridy++;
    addSection("Generations", gbc);
    addStat("Old Gen Used:", oldGenUsedLabel, gbc);
    addStat("Old Gen Committed:", oldGenCommittedLabel, gbc);
    addStat("Young Gen (Eden):", youngGenUsedLabel, gbc);
    addStat("Metaspace:", metaspaceUsedLabel, gbc);

    gbc.gridy++;
    addSection("Metrics", gbc);
    addStat("GC Efficiency:", gcEfficiencyLabel, gbc);
    addStat("Heap Growth:", heapGrowthLabel, gbc);
    addStat("Old Gen Growth:", oldGenGrowthLabel, gbc);

    // Add filler at the bottom
    gbc.weighty = 1.0;
    gbc.gridy++;
    add(new JPanel(), gbc);
  }

  private void addSection(String title, GridBagConstraints gbc) {
    JLabel label = new JBLabel(title);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    label.setBorder(JBUI.Borders.empty(10, 0, 5, 0));
    gbc.gridx = 0;
    gbc.gridwidth = 2;
    add(label, gbc);
    gbc.gridy++;
    gbc.gridwidth = 1;
  }

  private void addStat(String labelText, JLabel valueLabel, GridBagConstraints gbc) {
    gbc.gridx = 0;
    gbc.weightx = 0.5;
    JLabel label = new JBLabel(labelText);
    label.setFont(label.getFont().deriveFont(Font.PLAIN));
    add(label, gbc);

    gbc.gridx = 1;
    gbc.weightx = 0.5;
    valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN));
    add(valueLabel, gbc);

    gbc.gridy++;
  }

  public void updateStats(MemoryMonitorService.MemorySnapshot snapshot) {
    // Update values
    heapUsedLabel.setText(formatBytes(snapshot.heapUsed()));
    heapCommittedLabel.setText(formatBytes(snapshot.heapCommitted()));
    heapMaxLabel.setText(formatBytes(snapshot.heapMax()));
    oldGenUsedLabel.setText(formatBytes(snapshot.oldGenUsed()));
    oldGenCommittedLabel.setText(formatBytes(snapshot.oldGenCommitted()));
    youngGenUsedLabel.setText(formatBytes(snapshot.youngGenUsed()));
    metaspaceUsedLabel.setText(formatBytes(snapshot.metaspaceUsed()));

    // Calculate growth rates
    if (lastHeapUsed > 0) {
      double heapGrowth = ((snapshot.heapUsed() - lastHeapUsed) * 100.0) / lastHeapUsed;
      heapGrowthLabel.setText(String.format("%+.1f%%", heapGrowth));
      heapGrowthLabel.setForeground(heapGrowth > 0 ?
              new Color(255, 80, 80) : new Color(80, 255, 80));
    }

    if (lastOldGenUsed > 0) {
      double oldGenGrowth = ((snapshot.oldGenUsed() - lastOldGenUsed) * 100.0) / lastOldGenUsed;
      oldGenGrowthLabel.setText(String.format("%+.1f%%", oldGenGrowth));
      oldGenGrowthLabel.setForeground(oldGenGrowth > 0 ?
              new Color(255, 80, 80) : new Color(80, 255, 80));
    }

    lastHeapUsed = snapshot.heapUsed();
    lastOldGenUsed = snapshot.oldGenUsed();
  }

  public void updateGcEfficiency(double efficiency) {
    gcEfficiencyLabel.setText(String.format("%.1f%%", efficiency));
    if (efficiency < 20) {
      gcEfficiencyLabel.setForeground(new Color(255, 80, 80));
    } else if (efficiency < 50) {
      gcEfficiencyLabel.setForeground(new Color(255, 200, 80));
    } else {
      gcEfficiencyLabel.setForeground(new Color(80, 255, 80));
    }
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }
}
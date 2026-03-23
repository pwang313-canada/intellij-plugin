// src/main/java/org/cakk/memoryleak/ui/StatisticsPanel.java
package org.cakk.memoryleak.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;

import javax.swing.*;
import java.awt.*;
import java.text.DecimalFormat;

public class StatisticsPanel extends JPanel {

  // Memory Statistics Labels
  private final JBLabel heapUsedLabel;
  private final JBLabel heapCommittedLabel;
  private final JBLabel heapMaxLabel;
  private final JBLabel heapUsagePercentLabel;

  // Generation Statistics Labels
  private final JBLabel oldGenUsedLabel;
  private final JBLabel oldGenCommittedLabel;
  private final JBLabel oldGenUsagePercentLabel;
  private final JBLabel youngGenUsedLabel;
  private final JBLabel metaspaceUsedLabel;

  // Performance Metrics Labels
  private final JBLabel gcEfficiencyLabel;
  private final JBLabel heapGrowthLabel;
  private final JBLabel oldGenGrowthLabel;
  private final JBLabel recentGrowthRateLabel;

  // GC Statistics Labels
  private final JBLabel gcCountLabel;
  private final JBLabel totalGcTimeLabel;
  private final JBLabel avgGcTimeLabel;
  private final JBLabel lastGcTimeLabel;

  // Health Status
  private final JProgressBar heapHealthBar;
  private final JProgressBar oldGenHealthBar;
  private final JLabel healthStatusLabel;

  // Growth tracking
  private long lastHeapUsed = 0;
  private long lastOldGenUsed = 0;
  private int gcCount = 0;
  private long totalGcTime = 0;
  private long lastGcDuration = 0;

  private final DecimalFormat percentFormat = new DecimalFormat("0.0");
  private final DecimalFormat growthFormat = new DecimalFormat("+0.00;-0.00");

  public StatisticsPanel() {
    setLayout(new GridBagLayout());
    setBorder(JBUI.Borders.empty(10));

    // Initialize all labels
    heapUsedLabel = createValueLabel();
    heapCommittedLabel = createValueLabel();
    heapMaxLabel = createValueLabel();
    heapUsagePercentLabel = createValueLabel();

    oldGenUsedLabel = createValueLabel();
    oldGenCommittedLabel = createValueLabel();
    oldGenUsagePercentLabel = createValueLabel();
    youngGenUsedLabel = createValueLabel();
    metaspaceUsedLabel = createValueLabel();

    gcEfficiencyLabel = createValueLabel();
    heapGrowthLabel = createValueLabel();
    oldGenGrowthLabel = createValueLabel();
    recentGrowthRateLabel = createValueLabel();

    gcCountLabel = createValueLabel();
    totalGcTimeLabel = createValueLabel();
    avgGcTimeLabel = createValueLabel();
    lastGcTimeLabel = createValueLabel();

    // Health bars
    heapHealthBar = new JProgressBar(0, 100);
    heapHealthBar.setStringPainted(true);
    heapHealthBar.setPreferredSize(new Dimension(150, 20));

    oldGenHealthBar = new JProgressBar(0, 100);
    oldGenHealthBar.setStringPainted(true);
    oldGenHealthBar.setPreferredSize(new Dimension(150, 20));

    healthStatusLabel = new JBLabel("● HEALTHY");
    healthStatusLabel.setFont(healthStatusLabel.getFont().deriveFont(Font.BOLD, 12f));
    healthStatusLabel.setForeground(new Color(80, 255, 80));

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.insets = JBUI.insets(3, 5, 3, 5);

    // Section 1: Heap Memory
    addSection("HEAP MEMORY", gbc);
    addStat("Used:", heapUsedLabel, gbc);
    addStat("Committed:", heapCommittedLabel, gbc);
    addStat("Max:", heapMaxLabel, gbc);
    addStat("Usage:", heapUsagePercentLabel, gbc);
    addHealthBar("Heap Health:", heapHealthBar, gbc);

    gbc.gridy++;
    // Section 2: Generations
    addSection("GENERATIONS", gbc);
    addStat("Old Gen Used:", oldGenUsedLabel, gbc);
    addStat("Old Gen Committed:", oldGenCommittedLabel, gbc);
    addStat("Old Gen Usage:", oldGenUsagePercentLabel, gbc);
    addHealthBar("Old Gen Health:", oldGenHealthBar, gbc);
    addStat("Young Gen (Eden):", youngGenUsedLabel, gbc);
    addStat("Metaspace:", metaspaceUsedLabel, gbc);

    gbc.gridy++;
    // Section 3: Performance Metrics
    addSection("PERFORMANCE METRICS", gbc);
    addStat("GC Efficiency:", gcEfficiencyLabel, gbc);
    addStat("Heap Growth:", heapGrowthLabel, gbc);
    addStat("Old Gen Growth:", oldGenGrowthLabel, gbc);
    addStat("Recent Growth Rate:", recentGrowthRateLabel, gbc);

    gbc.gridy++;
    // Section 4: GC Statistics
    addSection("GC STATISTICS", gbc);
    addStat("GC Count:", gcCountLabel, gbc);
    addStat("Total GC Time:", totalGcTimeLabel, gbc);
    addStat("Avg GC Time:", avgGcTimeLabel, gbc);
    addStat("Last GC Time:", lastGcTimeLabel, gbc);

    gbc.gridy++;
    // Section 5: Health Status
    addSection("SYSTEM HEALTH", gbc);
    gbc.gridx = 0;
    gbc.gridwidth = 2;
    gbc.insets = JBUI.insets(5, 5, 10, 5);
    add(healthStatusLabel, gbc);

    // Add filler at the bottom
    gbc.weighty = 1.0;
    gbc.gridy++;
    add(new JPanel(), gbc);
  }

  private JBLabel createValueLabel() {
    JBLabel label = new JBLabel("--");
    label.setFont(label.getFont().deriveFont(Font.PLAIN));
    label.setHorizontalAlignment(SwingConstants.RIGHT);
    return label;
  }

  private void addSection(String title, GridBagConstraints gbc) {
    JLabel label = new JBLabel(title);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
    label.setBorder(JBUI.Borders.empty(8, 0, 3, 0));
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
    add(valueLabel, gbc);

    gbc.gridy++;
  }

  private void addHealthBar(String labelText, JProgressBar healthBar, GridBagConstraints gbc) {
    gbc.gridx = 0;
    gbc.weightx = 0.5;
    JLabel label = new JBLabel(labelText);
    label.setFont(label.getFont().deriveFont(Font.PLAIN));
    add(label, gbc);

    gbc.gridx = 1;
    gbc.weightx = 0.5;
    add(healthBar, gbc);

    gbc.gridy++;
  }

  // ========== LOCAL MONITORING METHODS ==========

  public void updateStats(MemoryMonitorService.MemorySnapshot snapshot) {
    // Update heap statistics
    heapUsedLabel.setText(formatBytes(snapshot.heapUsed()));
    heapCommittedLabel.setText(formatBytes(snapshot.heapCommitted()));
    heapMaxLabel.setText(formatBytes(snapshot.heapMax()));

    double heapUsagePercent = snapshot.heapCommitted() > 0 ?
            (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted() : 0;
    heapUsagePercentLabel.setText(String.format("%.1f%%", heapUsagePercent));
    updateHealthBar(heapHealthBar, heapUsagePercent);

    // Update generation statistics
    oldGenUsedLabel.setText(formatBytes(snapshot.oldGenUsed()));
    oldGenCommittedLabel.setText(formatBytes(snapshot.oldGenCommitted()));

    double oldGenUsagePercent = snapshot.oldGenCommitted() > 0 ?
            (snapshot.oldGenUsed() * 100.0) / snapshot.oldGenCommitted() : 0;
    oldGenUsagePercentLabel.setText(String.format("%.1f%%", oldGenUsagePercent));
    updateHealthBar(oldGenHealthBar, oldGenUsagePercent);

    youngGenUsedLabel.setText(formatBytes(snapshot.youngGenUsed()));
    metaspaceUsedLabel.setText(formatBytes(snapshot.metaspaceUsed()));

    // Calculate and update growth rates
    if (lastHeapUsed > 0) {
      double heapGrowth = ((snapshot.heapUsed() - lastHeapUsed) * 100.0) / lastHeapUsed;
      heapGrowthLabel.setText(formatGrowth(heapGrowth));
      updateGrowthColor(heapGrowthLabel, heapGrowth);
    }

    if (lastOldGenUsed > 0) {
      double oldGenGrowth = ((snapshot.oldGenUsed() - lastOldGenUsed) * 100.0) / lastOldGenUsed;
      oldGenGrowthLabel.setText(formatGrowth(oldGenGrowth));
      updateGrowthColor(oldGenGrowthLabel, oldGenGrowth);
    }

    lastHeapUsed = snapshot.heapUsed();
    lastOldGenUsed = snapshot.oldGenUsed();

    // Update overall health status
    updateHealthStatus(heapUsagePercent, oldGenUsagePercent);
  }

  public void updateGcEfficiency(double efficiency) {
    gcEfficiencyLabel.setText(String.format("%.1f%%", efficiency));
    if (efficiency < 10) {
      gcEfficiencyLabel.setForeground(new Color(255, 80, 80));
      gcEfficiencyLabel.setToolTipText("CRITICAL: Very poor GC efficiency - possible memory leak!");
    } else if (efficiency < 20) {
      gcEfficiencyLabel.setForeground(new Color(255, 200, 80));
      gcEfficiencyLabel.setToolTipText("WARNING: Poor GC efficiency");
    } else if (efficiency < 50) {
      gcEfficiencyLabel.setForeground(new Color(200, 200, 80));
      gcEfficiencyLabel.setToolTipText("Moderate GC efficiency");
    } else {
      gcEfficiencyLabel.setForeground(new Color(80, 255, 80));
      gcEfficiencyLabel.setToolTipText("Good GC efficiency");
    }
  }

  public void updateRecentGrowthRate(double growthRate) {
    recentGrowthRateLabel.setText(String.format("%.2f%%", growthRate));
    if (growthRate > 10) {
      recentGrowthRateLabel.setForeground(new Color(255, 80, 80));
      recentGrowthRateLabel.setToolTipText("⚠️ RAPID GROWTH: Memory leak suspected!");
    } else if (growthRate > 5) {
      recentGrowthRateLabel.setForeground(new Color(255, 200, 80));
      recentGrowthRateLabel.setToolTipText("Moderate growth - monitor closely");
    } else {
      recentGrowthRateLabel.setForeground(new Color(80, 255, 80));
      recentGrowthRateLabel.setToolTipText("Normal growth rate");
    }
  }

  public void updateHeapGrowth(double growthPercent) {
    heapGrowthLabel.setText(formatGrowth(growthPercent));
    updateGrowthColor(heapGrowthLabel, growthPercent);
  }

  public void updateOldGenGrowth(double growthPercent) {
    oldGenGrowthLabel.setText(formatGrowth(growthPercent));
    updateGrowthColor(oldGenGrowthLabel, growthPercent);
  }

  public void addGcEvent(long duration) {
    gcCount++;
    totalGcTime += duration;
    lastGcDuration = duration;

    gcCountLabel.setText(String.valueOf(gcCount));
    totalGcTimeLabel.setText(formatDuration(totalGcTime));
    avgGcTimeLabel.setText(formatDuration(gcCount > 0 ? totalGcTime / gcCount : 0));
    lastGcTimeLabel.setText(formatDuration(duration));
  }

  // ========== REMOTE MONITORING METHODS ==========

  public void updateRemoteStats(RemoteMemoryMonitorService.RemoteMemorySnapshot snapshot) {
    // Update heap statistics
    heapUsedLabel.setText(formatBytes(snapshot.heapUsed()));
    heapCommittedLabel.setText(formatBytes(snapshot.heapCommitted()));
    heapMaxLabel.setText(formatBytes(snapshot.heapMax()));

    double heapUsagePercent = snapshot.heapCommitted() > 0 ?
            (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted() : 0;
    heapUsagePercentLabel.setText(String.format("%.1f%%", heapUsagePercent));
    updateHealthBar(heapHealthBar, heapUsagePercent);

    // Update generation statistics
    oldGenUsedLabel.setText(formatBytes(snapshot.oldGenUsed()));
    oldGenCommittedLabel.setText(formatBytes(snapshot.oldGenCommitted()));

    double oldGenUsagePercent = snapshot.oldGenCommitted() > 0 ?
            (snapshot.oldGenUsed() * 100.0) / snapshot.oldGenCommitted() : 0;
    oldGenUsagePercentLabel.setText(String.format("%.1f%%", oldGenUsagePercent));
    updateHealthBar(oldGenHealthBar, oldGenUsagePercent);

    youngGenUsedLabel.setText(formatBytes(snapshot.youngGenUsed()));
    metaspaceUsedLabel.setText(formatBytes(snapshot.metaspaceUsed()));

    // Update growth rates for remote
    if (lastHeapUsed > 0) {
      double heapGrowth = ((snapshot.heapUsed() - lastHeapUsed) * 100.0) / lastHeapUsed;
      heapGrowthLabel.setText(formatGrowth(heapGrowth));
      updateGrowthColor(heapGrowthLabel, heapGrowth);
    }

    if (lastOldGenUsed > 0) {
      double oldGenGrowth = ((snapshot.oldGenUsed() - lastOldGenUsed) * 100.0) / lastOldGenUsed;
      oldGenGrowthLabel.setText(formatGrowth(oldGenGrowth));
      updateGrowthColor(oldGenGrowthLabel, oldGenGrowth);
    }

    lastHeapUsed = snapshot.heapUsed();
    lastOldGenUsed = snapshot.oldGenUsed();

    updateHealthStatus(heapUsagePercent, oldGenUsagePercent);
  }

  // ========== HELPER METHODS ==========

  private void updateHealthBar(JProgressBar bar, double percent) {
    int intPercent = (int) Math.round(percent);
    bar.setValue(intPercent);
    bar.setString(String.format("%d%%", intPercent));

    if (intPercent > 90) {
      bar.setForeground(new Color(255, 80, 80));
    } else if (intPercent > 75) {
      bar.setForeground(new Color(255, 200, 80));
    } else {
      bar.setForeground(new Color(80, 255, 80));
    }
  }

  private void updateHealthStatus(double heapPercent, double oldGenPercent) {
    if (heapPercent > 90 || oldGenPercent > 90) {
      healthStatusLabel.setText("⚠️ CRITICAL - High Memory Usage!");
      healthStatusLabel.setForeground(new Color(255, 80, 80));
    } else if (heapPercent > 75 || oldGenPercent > 75) {
      healthStatusLabel.setText("⚠️ WARNING - Elevated Memory Usage");
      healthStatusLabel.setForeground(new Color(255, 200, 80));
    } else {
      healthStatusLabel.setText("✅ HEALTHY - Normal Memory Usage");
      healthStatusLabel.setForeground(new Color(80, 255, 80));
    }
  }

  private void updateGrowthColor(JLabel label, double growthPercent) {
    if (growthPercent > 10) {
      label.setForeground(new Color(255, 80, 80));
      label.setToolTipText("Rapid growth detected!");
    } else if (growthPercent > 5) {
      label.setForeground(new Color(255, 200, 80));
      label.setToolTipText("Moderate growth");
    } else if (growthPercent < -5) {
      label.setForeground(new Color(80, 255, 80));
      label.setToolTipText("Memory decreased");
    } else {
      label.setForeground(new Color(150, 150, 150));
      label.setToolTipText("Stable");
    }
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  private String formatDuration(long millis) {
    if (millis < 1000) return millis + " ms";
    return String.format("%.2f s", millis / 1000.0);
  }

  private String formatGrowth(double growth) {
    if (growth > 0) {
      return String.format("+%.2f%%", growth);
    } else if (growth < 0) {
      return String.format("%.2f%%", growth);
    }
    return "0.00%";
  }

  public void resetGcStats() {
    gcCount = 0;
    totalGcTime = 0;
    lastGcDuration = 0;
    gcCountLabel.setText("0");
    totalGcTimeLabel.setText("0 ms");
    avgGcTimeLabel.setText("0 ms");
    lastGcTimeLabel.setText("0 ms");
  }

  public void clear() {
    heapUsedLabel.setText("--");
    heapCommittedLabel.setText("--");
    heapMaxLabel.setText("--");
    heapUsagePercentLabel.setText("--");
    oldGenUsedLabel.setText("--");
    oldGenCommittedLabel.setText("--");
    oldGenUsagePercentLabel.setText("--");
    youngGenUsedLabel.setText("--");
    metaspaceUsedLabel.setText("--");
    gcEfficiencyLabel.setText("--");
    heapGrowthLabel.setText("--");
    oldGenGrowthLabel.setText("--");
    recentGrowthRateLabel.setText("--");

    heapHealthBar.setValue(0);
    oldGenHealthBar.setValue(0);

    resetGcStats();

    lastHeapUsed = 0;
    lastOldGenUsed = 0;

    healthStatusLabel.setText("● HEALTHY");
    healthStatusLabel.setForeground(new Color(80, 255, 80));
  }
}
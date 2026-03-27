// src/main/java/org/cakk/memoryleak/ui/MemoryChartPanel.java
package org.cakk.memoryleak.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.cakk.memoryleak.services.RemoteMemoryMonitorService;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class MemoryChartPanel extends JPanel {

  private static final int MAX_DATA_POINTS = 200;
  private final List<DataPoint> heapData = new ArrayList<>();
  private final List<DataPoint> oldGenData = new ArrayList<>();
  private final List<DataPoint> youngGenData = new ArrayList<>();
  private long maxHeap = 0;
  private long maxOldGen = 0;
  private long maxYoungGen = 0;

  // Checkbox states
  private boolean showHeap = true;
  private boolean showOldGen = true;
  private boolean showYoungGen = true;

  // Checkbox components
  private final JCheckBox heapCheckbox;
  private final JCheckBox oldGenCheckbox;
  private final JCheckBox youngGenCheckbox;

  private final JPanel chartPanel;

  public MemoryChartPanel() {
    setBackground(JBColor.background());
    setBorder(JBUI.Borders.empty(5));
    setLayout(new BorderLayout());
    setPreferredSize(new Dimension(800, 380));

    // Create checkbox panel
    JPanel checkboxPanel = createCheckboxPanel();

    // Initialize checkboxes
    heapCheckbox = new JCheckBox("Heap Used", showHeap);
    oldGenCheckbox = new JCheckBox("Old Gen", showOldGen);
    youngGenCheckbox = new JCheckBox("Young Gen", showYoungGen);

    // Set checkbox colors to match chart lines
    heapCheckbox.setForeground(new JBColor(new Color(0, 100, 255), new Color(80, 140, 255)));
    oldGenCheckbox.setForeground(new JBColor(new Color(255, 80, 80), new Color(255, 120, 120)));
    youngGenCheckbox.setForeground(new JBColor(new Color(80, 255, 80), new Color(120, 255, 120)));

    // Add checkboxes to panel
    checkboxPanel.add(heapCheckbox);
    checkboxPanel.add(oldGenCheckbox);
    checkboxPanel.add(youngGenCheckbox);

    // Add action listeners
    heapCheckbox.addActionListener(e -> {
      showHeap = heapCheckbox.isSelected();
      recalculateMaxValues();
      repaint();
    });

    oldGenCheckbox.addActionListener(e -> {
      showOldGen = oldGenCheckbox.isSelected();
      recalculateMaxValues();
      repaint();
    });

    youngGenCheckbox.addActionListener(e -> {
      showYoungGen = youngGenCheckbox.isSelected();
      recalculateMaxValues();
      repaint();
    });

    // Create chart panel
    chartPanel = new JPanel() {
      @Override
      protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        drawChart(g);
      }
    };
    chartPanel.setBackground(JBColor.background());
    chartPanel.setPreferredSize(new Dimension(800, 300));

    // Add components to main panel
    add(checkboxPanel, BorderLayout.NORTH);
    add(chartPanel, BorderLayout.CENTER);
  }

  private JPanel createCheckboxPanel() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    panel.setBackground(JBColor.background());
    panel.setBorder(JBUI.Borders.empty(5, 10, 5, 10));
    return panel;
  }

  private void recalculateMaxValues() {
    maxHeap = 0;
    maxOldGen = 0;
    maxYoungGen = 0;

    if (showHeap) {
      for (DataPoint dp : heapData) {
        maxHeap = Math.max(maxHeap, dp.value());
      }
    }

    if (showOldGen) {
      for (DataPoint dp : oldGenData) {
        maxOldGen = Math.max(maxOldGen, dp.value());
      }
    }

    if (showYoungGen) {
      for (DataPoint dp : youngGenData) {
        maxYoungGen = Math.max(maxYoungGen, dp.value());
      }
    }
  }

  private void drawChart(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int width = chartPanel.getWidth() - 100;
    int height = chartPanel.getHeight() - 60;
    int startX = 60;
    int startY = 30;

    // Draw background
    g2.setColor(JBColor.background());
    g2.fillRect(0, 0, chartPanel.getWidth(), chartPanel.getHeight());

    // Draw axes
    g2.setColor(JBColor.border());
    g2.drawLine(startX, startY, startX, startY + height);
    g2.drawLine(startX, startY + height, startX + width, startY + height);

    // Draw grid lines
    g2.setColor(new JBColor(new Color(200, 200, 200), new Color(80, 80, 80)));
    for (int i = 0; i <= 4; i++) {
      int y = startY + (height * i / 4);
      g2.drawLine(startX, y, startX + width, y);
    }

    // Draw labels
    g2.setColor(JBColor.foreground());
    g2.drawString("Memory (MB)", 10, startY + height / 2);
    g2.drawString("Time (seconds)", startX + width / 2, startY + height + 20);

    // Calculate max value for Y-axis scaling based on visible datasets
    long maxValue = 0;
    if (showHeap) maxValue = Math.max(maxValue, maxHeap);
    if (showOldGen) maxValue = Math.max(maxValue, maxOldGen);
    if (showYoungGen) maxValue = Math.max(maxValue, maxYoungGen);

    // Draw Y-axis labels
    if (maxValue > 0) {
      for (int i = 0; i <= 4; i++) {
        long labelValue = (maxValue * i) / 4;
        int y = startY + height - (height * i / 4);
        g2.drawString(formatBytes(labelValue), startX - 45, y + 4);
      }
    }

    // Draw data based on checkbox states
    if (showHeap && !heapData.isEmpty() && maxHeap > 0) {
      drawLineChart(g2, heapData, new JBColor(new Color(0, 100, 255), new Color(80, 140, 255)),
              startX, startY, width, height, maxHeap);
    }
    if (showOldGen && !oldGenData.isEmpty() && maxOldGen > 0) {
      drawLineChart(g2, oldGenData, new JBColor(new Color(255, 80, 80), new Color(255, 120, 120)),
              startX, startY, width, height, maxOldGen);
    }
    if (showYoungGen && !youngGenData.isEmpty() && maxYoungGen > 0) {
      drawLineChart(g2, youngGenData, new JBColor(new Color(80, 255, 80), new Color(120, 255, 120)),
              startX, startY, width, height, maxYoungGen);
    }

    // Draw legend with checkboxes
    drawLegend(g2, startX + width - 120, startY);

    g2.dispose();
  }

  // ========== PUBLIC METHODS ==========

  /**
   * Add data point from local monitoring
   */
  public void addLocalDataPoint(MemoryMonitorService.MemorySnapshot snapshot) {
    addDataPoint(snapshot.heapUsed(), snapshot.oldGenUsed(), snapshot.youngGenUsed());
  }

  /**
   * Add data point from remote monitoring
   */
  public void addRemoteDataPoint(RemoteMemoryMonitorService.RemoteMemorySnapshot snapshot) {
    addDataPoint(snapshot.heapUsed(), snapshot.oldGenUsed(), snapshot.youngGenUsed());
  }

  /**
   * Add data point from legacy method (for backward compatibility)
   */
  public void addDataPoint(MemoryMonitorService.MemorySnapshot snapshot) {
    addDataPoint(snapshot.heapUsed(), snapshot.oldGenUsed(), snapshot.youngGenUsed());
  }

  /**
   * Core method to add data points
   */
  private void addDataPoint(long heapUsed, long oldGenUsed, long youngGenUsed) {
    long timestamp = System.currentTimeMillis();
    heapData.add(new DataPoint(timestamp, heapUsed));
    oldGenData.add(new DataPoint(timestamp, oldGenUsed));
    youngGenData.add(new DataPoint(timestamp, youngGenUsed));

    // Trim data if exceeds max points
    while (heapData.size() > MAX_DATA_POINTS) {
      heapData.remove(0);
    }
    while (oldGenData.size() > MAX_DATA_POINTS) {
      oldGenData.remove(0);
    }
    while (youngGenData.size() > MAX_DATA_POINTS) {
      youngGenData.remove(0);
    }

    // Update max values for scaling (only for visible datasets)
    if (showHeap) {
      maxHeap = Math.max(maxHeap, heapUsed);
    }
    if (showOldGen) {
      maxOldGen = Math.max(maxOldGen, oldGenUsed);
    }
    if (showYoungGen) {
      maxYoungGen = Math.max(maxYoungGen, youngGenUsed);
    }

    chartPanel.repaint();
  }

  /**
   * Clear all data points
   */
  public void clear() {
    heapData.clear();
    oldGenData.clear();
    youngGenData.clear();
    maxHeap = 0;
    maxOldGen = 0;
    maxYoungGen = 0;
    chartPanel.repaint();
  }

  /**
   * Get the checkbox for heap display
   */
  public JCheckBox getHeapCheckbox() {
    return heapCheckbox;
  }

  /**
   * Get the checkbox for old gen display
   */
  public JCheckBox getOldGenCheckbox() {
    return oldGenCheckbox;
  }

  /**
   * Get the checkbox for young gen display
   */
  public JCheckBox getYoungGenCheckbox() {
    return youngGenCheckbox;
  }

  /**
   * Set which metrics to display programmatically
   */
  public void setDisplayOptions(boolean showHeap, boolean showOldGen, boolean showYoungGen) {
    this.showHeap = showHeap;
    this.showOldGen = showOldGen;
    this.showYoungGen = showYoungGen;

    if (heapCheckbox != null) heapCheckbox.setSelected(showHeap);
    if (oldGenCheckbox != null) oldGenCheckbox.setSelected(showOldGen);
    if (youngGenCheckbox != null) youngGenCheckbox.setSelected(showYoungGen);

    recalculateMaxValues();
    chartPanel.repaint();
  }

  // ========== PAINTING METHODS ==========

  private void drawLineChart(Graphics2D g2, List<DataPoint> data, Color color,
                             int startX, int startY, int width, int height, long maxValue) {
    if (data.size() < 2 || maxValue == 0) return;

    g2.setColor(color);
    g2.setStroke(new BasicStroke(2f));

    double xStep = (double) width / MAX_DATA_POINTS;

    // Calculate first point
    double firstY = startY + height - (data.get(0).value() * height / maxValue);
    double lastX = startX;
    double lastY = firstY;

    for (int i = 1; i < data.size(); i++) {
      double x = startX + i * xStep;
      double y = startY + height - (data.get(i).value() * height / maxValue);

      // Only draw if y is within bounds
      if (y >= startY && y <= startY + height) {
        g2.drawLine((int) lastX, (int) lastY, (int) x, (int) y);
      }

      lastX = x;
      lastY = y;
    }
  }

  private void drawLegend(Graphics2D g2, int x, int y) {
    int currentY = y;

    // Only show legend items for visible datasets
    if (showHeap) {
      g2.setColor(new JBColor(new Color(0, 100, 255), new Color(80, 140, 255)));
      g2.fillRect(x, currentY, 20, 10);
      g2.setColor(JBColor.foreground());
      g2.drawString("Heap Used", x + 25, currentY + 10);
      currentY += 20;
    }

    if (showOldGen) {
      g2.setColor(new JBColor(new Color(255, 80, 80), new Color(255, 120, 120)));
      g2.fillRect(x, currentY, 20, 10);
      g2.setColor(JBColor.foreground());
      g2.drawString("Old Gen", x + 25, currentY + 10);
      currentY += 20;
    }

    if (showYoungGen) {
      g2.setColor(new JBColor(new Color(80, 255, 80), new Color(120, 255, 120)));
      g2.fillRect(x, currentY, 20, 10);
      g2.setColor(JBColor.foreground());
      g2.drawString("Young Gen", x + 25, currentY + 10);
    }
  }

  private String formatBytes(long bytes) {
    double mb = bytes / (1024.0 * 1024.0);
    if (mb < 1) {
      return String.format("%.0f KB", bytes / 1024.0);
    }
    return String.format("%.0f MB", mb);
  }

  // ========== INNER CLASS ==========

  private record DataPoint(long timestamp, long value) {}
}
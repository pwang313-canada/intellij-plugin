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
  private long maxHeap = 0;
  private long maxOldGen = 0;

  public MemoryChartPanel() {
    setBackground(JBColor.background());
    setBorder(JBUI.Borders.empty(5));
    setPreferredSize(new Dimension(800, 300));
  }

  // ========== PUBLIC METHODS ==========

  /**
   * Add data point from local monitoring
   */
  public void addLocalDataPoint(MemoryMonitorService.MemorySnapshot snapshot) {
    addDataPoint(snapshot.heapUsed(), snapshot.oldGenUsed());
  }

  /**
   * Add data point from remote monitoring
   * FIXED: Use RemoteMemorySnapshot, not MemorySnapshot
   */
  public void addRemoteDataPoint(RemoteMemoryMonitorService.RemoteMemorySnapshot snapshot) {
    addDataPoint(snapshot.heapUsed(), snapshot.oldGenUsed());
  }

  /**
   * Add data point from legacy method (for backward compatibility)
   */
  public void addDataPoint(MemoryMonitorService.MemorySnapshot snapshot) {
    addDataPoint(snapshot.heapUsed(), snapshot.oldGenUsed());
  }

  /**
   * Core method to add data points
   */
  private void addDataPoint(long heapUsed, long oldGenUsed) {
    long timestamp = System.currentTimeMillis();
    heapData.add(new DataPoint(timestamp, heapUsed));
    oldGenData.add(new DataPoint(timestamp, oldGenUsed));

    // Trim data if exceeds max points
    while (heapData.size() > MAX_DATA_POINTS) {
      heapData.remove(0);
    }
    while (oldGenData.size() > MAX_DATA_POINTS) {
      oldGenData.remove(0);
    }

    // Update max values for scaling
    maxHeap = Math.max(maxHeap, heapUsed);
    maxOldGen = Math.max(maxOldGen, oldGenUsed);

    repaint();
  }

  /**
   * Clear all data points
   */
  public void clear() {
    heapData.clear();
    oldGenData.clear();
    maxHeap = 0;
    maxOldGen = 0;
    repaint();
  }

  // ========== PAINTING METHODS ==========

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int width = getWidth() - 100;
    int height = getHeight() - 60;
    int startX = 60;
    int startY = 30;

    // Draw background
    g2.setColor(JBColor.background());
    g2.fillRect(0, 0, getWidth(), getHeight());

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

    // Draw Y-axis labels
    long maxValue = Math.max(maxHeap, maxOldGen);
    if (maxValue > 0) {
      for (int i = 0; i <= 4; i++) {
        long labelValue = (maxValue * i) / 4;
        int y = startY + height - (height * i / 4);
        g2.drawString(formatBytes(labelValue), startX - 45, y + 4);
      }
    }

    // Draw data - FIXED: Pass correct max values
    if (!heapData.isEmpty() && maxHeap > 0) {
      drawLineChart(g2, heapData, new JBColor(new Color(0, 100, 255), new Color(80, 140, 255)),
              startX, startY, width, height, maxHeap);
    }
    if (!oldGenData.isEmpty() && maxOldGen > 0) {
      drawLineChart(g2, oldGenData, new JBColor(new Color(255, 80, 80), new Color(255, 120, 120)),
              startX, startY, width, height, maxOldGen);
    }

    // Draw legend
    drawLegend(g2, startX + width - 120, startY);

    g2.dispose();
  }

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
    // Heap legend
    g2.setColor(new JBColor(new Color(0, 100, 255), new Color(80, 140, 255)));
    g2.fillRect(x, y, 20, 10);
    g2.setColor(JBColor.foreground());
    g2.drawString("Heap Used", x + 25, y + 10);

    // Old Gen legend
    g2.setColor(new JBColor(new Color(255, 80, 80), new Color(255, 120, 120)));
    g2.fillRect(x, y + 20, 20, 10);
    g2.setColor(JBColor.foreground());
    g2.drawString("Old Gen", x + 25, y + 30);
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
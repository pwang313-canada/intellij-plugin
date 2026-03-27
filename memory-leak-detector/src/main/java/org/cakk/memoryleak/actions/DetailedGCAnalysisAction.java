package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.NotNull;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;

public class DetailedGCAnalysisAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    MemoryMonitorService monitor = project.getService(MemoryMonitorService.class);

    if (monitor == null) {
      Messages.showErrorDialog(project, "Memory Monitor Service not available", "Error");
      return;
    }

    // Run analysis with progress indicator
    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Analyzing GC Effectiveness", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          indicator.setText("Taking memory snapshot...");
          indicator.setFraction(0.1);

          MemorySnapshot before = getDetailedMemorySnapshot();

          indicator.setText("Forcing garbage collection...");
          indicator.setFraction(0.3);

          System.gc();
          Thread.sleep(1000);
          System.gc();
          Thread.sleep(1000);
          System.runFinalization();

          indicator.setText("Taking after snapshot...");
          indicator.setFraction(0.6);

          MemorySnapshot after = getDetailedMemorySnapshot();

          indicator.setText("Analyzing results...");
          indicator.setFraction(0.8);

          String report = generateDetailedReport(before, after);

          ApplicationManager.getApplication().invokeLater(() -> {
            showResults(project, report, monitor);
          });

          indicator.setFraction(1.0);

        } catch (Exception ex) {
          ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, "Analysis failed: " + ex.getMessage(), "Error");
          });
        }
      }
    });
  }

  private MemorySnapshot getDetailedMemorySnapshot() {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heap = memoryBean.getHeapMemoryUsage();

    // Get old gen specifically
    long oldGenUsed = 0;
    long oldGenCommitted = 0;
    long youngGenUsed = 0;
    long metaspaceUsed = 0;

    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
    for (MemoryPoolMXBean pool : pools) {
      String name = pool.getName();
      MemoryUsage usage = pool.getUsage();

      if (name.contains("Old") || name.contains("Tenured") ||
              name.equals("PS Old Gen") || name.equals("G1 Old Gen")) {
        oldGenUsed = usage.getUsed();
        oldGenCommitted = usage.getCommitted();
      } else if (name.contains("Eden") || name.contains("Young") ||
              name.equals("PS Eden Space") || name.equals("G1 Eden Space")) {
        youngGenUsed = usage.getUsed();
      } else if (name.contains("Metaspace") || name.equals("Compressed Class Space")) {
        metaspaceUsed = usage.getUsed();
      }
    }

    return new MemorySnapshot(
            System.currentTimeMillis(),
            heap.getUsed(),
            heap.getCommitted(),
            heap.getMax(),
            oldGenUsed,
            oldGenCommitted,
            youngGenUsed,
            metaspaceUsed
    );
  }

  private String generateDetailedReport(MemorySnapshot before, MemorySnapshot after) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n");
    sb.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
    sb.append("║                    DETAILED GC EFFECTIVENESS ANALYSIS                         ║\n");
    sb.append("╚══════════════════════════════════════════════════════════════════════════════╝\n\n");

    // Heap comparison
    long heapRecovered = before.heapUsed - after.heapUsed;
    double heapRecoveryPercent = before.heapUsed > 0 ? (heapRecovered * 100.0) / before.heapUsed : 0;

    sb.append("📊 HEAP MEMORY:\n");
    sb.append(String.format("   Before GC: %s\n", formatBytes(before.heapUsed)));
    sb.append(String.format("   After GC:  %s\n", formatBytes(after.heapUsed)));
    sb.append(String.format("   Recovered: %s (%.1f%%)\n", formatBytes(heapRecovered), heapRecoveryPercent));

    if (heapRecoveryPercent < 10 && after.heapUsed > 100 * 1024 * 1024) {
      sb.append("   🔴 CRITICAL: GC recovered very little memory!\n");
      sb.append("   💡 This indicates a SEVERE memory leak!\n");
    } else if (heapRecoveryPercent < 30) {
      sb.append("   🟡 WARNING: GC recovered less than 30% of memory.\n");
      sb.append("   💡 Potential memory leak detected.\n");
    } else {
      sb.append("   ✅ GC recovered significant memory.\n");
    }

    sb.append("\n📊 OLD GENERATION (The main indicator of memory leaks):\n");
    long oldGenRecovered = before.oldGenUsed - after.oldGenUsed;
    double oldGenRecoveryPercent = before.oldGenUsed > 0 ? (oldGenRecovered * 100.0) / before.oldGenUsed : 0;

    sb.append(String.format("   Before GC: %s\n", formatBytes(before.oldGenUsed)));
    sb.append(String.format("   After GC:  %s\n", formatBytes(after.oldGenUsed)));
    sb.append(String.format("   Recovered: %s (%.1f%%)\n", formatBytes(oldGenRecovered), oldGenRecoveryPercent));

    // This is the key indicator for memory leaks
    if (oldGenRecoveryPercent < 10 && after.oldGenUsed > 50 * 1024 * 1024) {
      sb.append("\n   🔴🔴🔴 CRITICAL MEMORY LEAK DETECTED! 🔴🔴🔴\n");
      sb.append("   Old generation barely decreased after GC!\n");
      sb.append("   Objects are being held in memory and cannot be collected.\n");
      sb.append("   💡 ACTION REQUIRED: Click 'Find Memory Leaks' to locate the source.\n");
    } else if (oldGenRecoveryPercent < 30) {
      sb.append("\n   🟡 WARNING: Old generation decreased by less than 30%.\n");
      sb.append("   Potential memory leak - objects are surviving GC.\n");
    } else {
      sb.append("\n   ✅ Old generation cleaned up well. No severe leak.\n");
    }

    sb.append("\n📊 YOUNG GENERATION:\n");
    sb.append(String.format("   Before GC: %s\n", formatBytes(before.youngGenUsed)));
    sb.append(String.format("   After GC:  %s\n", formatBytes(after.youngGenUsed)));
    long youngRecovered = before.youngGenUsed - after.youngGenUsed;
    sb.append(String.format("   Recovered: %s\n", formatBytes(youngRecovered)));

    sb.append("\n📊 METASPACE:\n");
    sb.append(String.format("   Before GC: %s\n", formatBytes(before.metaspaceUsed)));
    sb.append(String.format("   After GC:  %s\n", formatBytes(after.metaspaceUsed)));

    // Summary and recommendations
    sb.append("\n╔══════════════════════════════════════════════════════════════════════════════╗\n");
    sb.append("║                           RECOMMENDATIONS                                      ║\n");
    sb.append("╚══════════════════════════════════════════════════════════════════════════════╝\n\n");

    if (oldGenRecoveryPercent < 10 && after.oldGenUsed > 50 * 1024 * 1024) {
      sb.append("1. 🔴 IMMEDIATE ACTION: Click 'Find Memory Leaks' to locate the leak\n");
      sb.append("2. 📍 Check the following common leak patterns:\n");
      sb.append("   • Static collections (List, Map, Set) that keep growing\n");
      sb.append("   • Event listeners that are never unregistered\n");
      sb.append("   • ThreadLocal variables without remove() calls\n");
      sb.append("   • Caches without eviction policies\n");
      sb.append("   • Inner classes holding outer class references\n");
      sb.append("3. 🔧 Run 'Analyze Memory After GC' to get detailed leak locations\n");
    } else if (oldGenRecoveryPercent < 30) {
      sb.append("1. 🟡 Monitor closely - potential memory leak detected\n");
      sb.append("2. 📊 Run 'Find Memory Leaks' to analyze allocation patterns\n");
      sb.append("3. 💡 Review code for objects that might be held longer than needed\n");
    } else {
      sb.append("✅ Memory usage looks healthy. No immediate action needed.\n");
      sb.append("💡 Continue monitoring for unusual growth patterns.\n");
    }

    return sb.toString();
  }

  private void showResults(Project project, String report, MemoryMonitorService monitor) {
    int result = Messages.showYesNoDialog(
            project,
            report,
            "GC Effectiveness Analysis",
            "Find Memory Leaks",
            "Close",
            Messages.getInformationIcon()
    );

    if (result == Messages.YES) {
      // Direct method call - simplest and most reliable
      monitor.forceLeakDetection();
    }

    // Also log to console
    System.out.println(report);
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  private static class MemorySnapshot {
    final long timestamp;
    final long heapUsed;
    final long heapCommitted;
    final long heapMax;
    final long oldGenUsed;
    final long oldGenCommitted;
    final long youngGenUsed;
    final long metaspaceUsed;

    MemorySnapshot(long timestamp, long heapUsed, long heapCommitted, long heapMax,
                   long oldGenUsed, long oldGenCommitted, long youngGenUsed, long metaspaceUsed) {
      this.timestamp = timestamp;
      this.heapUsed = heapUsed;
      this.heapCommitted = heapCommitted;
      this.heapMax = heapMax;
      this.oldGenUsed = oldGenUsed;
      this.oldGenCommitted = oldGenCommitted;
      this.youngGenUsed = youngGenUsed;
      this.metaspaceUsed = metaspaceUsed;
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabled(project != null);
  }
}
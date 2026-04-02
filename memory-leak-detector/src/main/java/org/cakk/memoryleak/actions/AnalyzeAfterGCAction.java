package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.cakk.memoryleak.services.AllocationInterceptor;
import org.cakk.memoryleak.services.AllocationTracker;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

public class AnalyzeAfterGCAction extends AnAction {

  private static final Logger LOG = Logger.getInstance(AnalyzeAfterGCAction.class);
  private static long beforeGcHeap = 0;
  private static long afterGcHeap = 0;

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    MemoryMonitorService monitor = project.getService(MemoryMonitorService.class);

    if (monitor == null) {
      Messages.showErrorDialog(project,
              "Memory Monitor Service not available",
              "Error");
      return;
    }

    // Ask user if they want heap dump (optional)
    int heapDumpOption = Messages.showYesNoCancelDialog(
            project,
            "Memory leak analysis will force GC and analyze survivors.\n\n" +
                    "Do you also want to create a heap dump for deeper analysis?\n" +
                    "Heap dump can be analyzed with tools like Eclipse MAT.",
            "Analysis Options",
            "Analyze Only",
            "Analyze with Heap Dump",
            "Cancel",
            Messages.getQuestionIcon()
    );

    if (heapDumpOption == Messages.CANCEL) {
      return;
    }

    final boolean createHeapDump = (heapDumpOption == Messages.NO);

    // Run analysis with progress indicator
    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Analyzing Memory Leaks", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(false);
        indicator.setFraction(0.0);
        indicator.setText("Analyzing memory usage...");

        try {
          // Step 1: Get current heap size
          beforeGcHeap = monitor.getCurrentSnapshot().heapUsed();
          indicator.setFraction(0.15);
          indicator.setText("Forcing garbage collection...");

          // Step 2: Force GC
          System.gc();
          Thread.sleep(2000);

          // Step 3: Get heap after GC
          afterGcHeap = monitor.getCurrentSnapshot().heapUsed();
          indicator.setFraction(0.3);
          indicator.setText("Analyzing survivors...");

          // Step 4: Analyze survivors
          AllocationInterceptor interceptor = monitor.getAllocationInterceptor();
          if (interceptor != null) {
            interceptor.analyzeSurvivors();
          }

          indicator.setFraction(0.45);
          indicator.setText("Finding leak suspects...");

          // Step 5: Get top suspects
          List<AllocationTracker.AllocationInfo> suspects =
                  monitor.getAllocationTracker().getTopSuspects(10);

          indicator.setFraction(0.6);
          indicator.setText("Generating report...");

          // Step 6: Generate report
          final String report = generateReport(beforeGcHeap, afterGcHeap, suspects);

          // Step 7: Create heap dump if requested
          if (createHeapDump) {
            indicator.setText("Creating heap dump...");
            analyzeWithHeapDump(project, monitor);
          }

          indicator.setFraction(0.9);
          indicator.setText("Finalizing...");

          // Step 8: Show results in UI
          ApplicationManager.getApplication().invokeLater(() -> {
            showResults(project, report, suspects, createHeapDump);
          });

          indicator.setFraction(1.0);

        } catch (Exception ex) {
          LOG.error("Error analyzing memory", ex);
          ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project,
                    "Error analyzing memory: " + ex.getMessage(),
                    "Analysis Error");
          });
        }
      }
    });
  }

  /**
   * Create heap dump for deeper analysis
   */
  private void analyzeWithHeapDump(Project project, MemoryMonitorService monitor) {
    try {
      // Create heap dump
      String timestamp = String.valueOf(System.currentTimeMillis());
      String dumpPath = System.getProperty("java.io.tmpdir") +
              "heapdump-" + timestamp + ".hprof";

      // Get HotSpot Diagnostic MXBean
      java.lang.management.MemoryMXBean memoryBean =
              java.lang.management.ManagementFactory.getMemoryMXBean();

      com.sun.management.HotSpotDiagnosticMXBean diagnosticBean =
              java.lang.management.ManagementFactory.getPlatformMXBean(
                      com.sun.management.HotSpotDiagnosticMXBean.class);

      if (diagnosticBean != null) {
        diagnosticBean.dumpHeap(dumpPath, true);
        LOG.info("Heap dump created at: " + dumpPath);

        // Notify user
        ApplicationManager.getApplication().invokeLater(() -> {
          int result = Messages.showYesNoDialog(
                  project,
                  "Heap dump created at:\n" + dumpPath + "\n\n" +
                          "Do you want to open the directory containing the heap dump?",
                  "Heap Dump Created",
                  "Open Directory",
                  "Close",
                  Messages.getInformationIcon()
          );

          if (result == Messages.YES) {
            // Open directory containing heap dump
            java.io.File dumpFile = new java.io.File(dumpPath);
            java.io.File parentDir = dumpFile.getParentFile();
            if (parentDir != null && parentDir.exists()) {
              try {
                java.awt.Desktop.getDesktop().open(parentDir);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            }
          }
        });
      } else {
        LOG.warn("HotSpotDiagnosticMXBean not available");
      }

    } catch (Exception e) {
      LOG.warn("Failed to create heap dump: " + e.getMessage());
      ApplicationManager.getApplication().invokeLater(() -> {
        Messages.showWarningDialog(
                project,
                "Failed to create heap dump: " + e.getMessage() + "\n\n" +
                        "You may need to run IntelliJ with:\n" +
                        "-XX:+UnlockDiagnosticVMOptions -XX:+HeapDumpOnOutOfMemoryError",
                "Heap Dump Failed"
        );
      });
    }
  }

  private String generateReport(long beforeGc, long afterGc,
                                List<AllocationTracker.AllocationInfo> suspects) {
    StringBuilder report = new StringBuilder();
    report.append("═══════════════════════════════════════════════════════════════\n");
    report.append("           MEMORY LEAK ANALYSIS REPORT\n");
    report.append("═══════════════════════════════════════════════════════════════\n\n");

    // GC Effectiveness
    long recovered = beforeGc - afterGc;
    double recoveryPercent = beforeGc > 0 ? (recovered * 100.0) / beforeGc : 0;

    report.append("📊 GC EFFECTIVENESS:\n");
    report.append(String.format("   Before GC: %s\n", formatBytes(beforeGc)));
    report.append(String.format("   After GC:  %s\n", formatBytes(afterGc)));
    report.append(String.format("   Recovered: %s (%.1f%%)\n", formatBytes(recovered), recoveryPercent));

    if (recoveryPercent < 10 && afterGc > 10 * 1024 * 1024) {
      report.append("   ⚠️  CRITICAL: GC recovered very little memory!\n");
      report.append("   💡 This indicates objects are being held by strong references.\n");
      report.append("   📌 Run 'Find Memory Leaks' to locate the source.\n");
    } else if (recoveryPercent < 30) {
      report.append("   ⚠️  WARNING: GC recovered less than 30% of memory.\n");
    } else {
      report.append("   ✅ GC recovered significant memory.\n");
    }

    report.append("\n🔍 TOP MEMORY LEAK SUSPECTS:\n");
    report.append("═══════════════════════════════════════════════════════════════\n\n");

    if (suspects == null || suspects.isEmpty()) {
      report.append("No memory leak suspects found.\n");
      report.append("Consider running the application longer to accumulate more data.\n");
      report.append("You can also:\n");
      report.append("  • Click 'Simulate Memory Leak' to test detection\n");
      report.append("  • Run your application code that you suspect is leaking\n");
      report.append("  • Enable more verbose logging in settings\n");
    } else {
      for (int i = 0; i < Math.min(10, suspects.size()); i++) {
        AllocationTracker.AllocationInfo info = suspects.get(i);
        report.append(String.format("%d. 📍 %s:%d\n", i + 1,
                info.fileName != null ? info.fileName : "unknown",
                info.lineNumber));
        report.append(String.format("   Class: %s\n", info.className));
        report.append(String.format("   Method: %s()\n", info.methodName));
        report.append(String.format("   Memory: %s (%d allocations)\n",
                formatBytes(info.totalMemory.get()), info.allocationCount.get()));
        report.append(String.format("   Surviving objects: %d\n", info.getSurvivingObjects()));

        // Show survival rate
        int surviving = info.getSurvivingObjects();
        long avgSizePerObject = info.allocationCount.get() > 0 ?
                info.totalMemory.get() / info.allocationCount.get() : 0;
        report.append(String.format("   Avg size per object: %s\n", formatBytes(avgSizePerObject)));

        if (surviving > 0 && info.allocationCount.get() > 0) {
          double survivalRate = (surviving * 100.0) / info.allocationCount.get();
          if (survivalRate > 50) {
            report.append("   🔴 HIGH RISK: >50% of objects survived GC!\n");
          } else if (survivalRate > 20) {
            report.append("   🟡 MEDIUM RISK: >20% of objects survived GC.\n");
          }
        }
        report.append("\n");
      }
    }

    return report.toString();
  }

  private void showResults(Project project, String report,
                           List<AllocationTracker.AllocationInfo> suspects,
                           boolean heapDumpCreated) {

    String dialogMessage = report;
    if (heapDumpCreated) {
      dialogMessage += "\n\n📁 Heap dump was created in your temp directory.\n" +
              "You can analyze it with Eclipse MAT or VisualVM.";
    }

    // Show in dialog
    int result = Messages.showYesNoDialog(
            project,
            dialogMessage,
            "Memory Leak Analysis Results",
            "Navigate to Leak",
            "Close",
            Messages.getInformationIcon()
    );

    // If user wants to navigate to leak
    if (result == Messages.YES && suspects != null && !suspects.isEmpty()) {
      AllocationTracker.AllocationInfo topSuspect = suspects.get(0);
      navigateToLocation(project, topSuspect);
    }

    // Also log to console
    System.out.println(report);
    LOG.info(report);
  }

  private void navigateToLocation(Project project, AllocationTracker.AllocationInfo info) {
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        VirtualFile file = null;

        // Try to find by full path
        if (info.fileName != null) {
          file = LocalFileSystem.getInstance().findFileByPath(info.fileName);
        }

        // Try to find by name in project
        if (file == null && info.fileName != null) {
          file = findFileByName(project, info.fileName);
        }

        if (file != null) {
          // Open the file at the specific line
          OpenFileDescriptor descriptor = new OpenFileDescriptor(
                  project, file, Math.max(0, info.lineNumber - 1), 0);

          FileEditorManager.getInstance(project).openTextEditor(descriptor, true);

          Messages.showInfoMessage(
                  project,
                  String.format("Navigated to %s:%d\n\n%s.%s()\n\n" +
                                  "Memory: %s\nSurviving objects: %d",
                          info.fileName, info.lineNumber,
                          info.className, info.methodName,
                          formatBytes(info.totalMemory.get()),
                          info.getSurvivingObjects()),
                  "Leak Location"
          );
        } else {
          Messages.showWarningDialog(
                  project,
                  String.format("Could not find file: %s\n\n%s.%s() at line %d\n\n" +
                                  "The file may not be in the current project or may have been renamed.\n\n" +
                                  "Check the console for full report.",
                          info.fileName, info.className, info.methodName, info.lineNumber),
                  "File Not Found"
          );
        }
      } catch (Exception e) {
        LOG.error("Error navigating to leak location", e);
        Messages.showErrorDialog(
                project,
                "Error navigating to leak location: " + e.getMessage(),
                "Navigation Error"
        );
      }
    });
  }

  private VirtualFile findFileByName(Project project, String fileName) {
    if (fileName == null || project == null) return null;

    VirtualFile baseDir = project.getBaseDir();
    if (baseDir == null) return null;

    return findFileRecursive(baseDir, fileName);
  }

  private VirtualFile findFileRecursive(VirtualFile dir, String fileName) {
    if (dir == null) return null;

    for (VirtualFile file : dir.getChildren()) {
      if (file.isDirectory()) {
        VirtualFile found = findFileRecursive(file, fileName);
        if (found != null) return found;
      } else if (file.getName().equals(fileName)) {
        return file;
      }
    }
    return null;
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabled(project != null);

    // Update text based on state (optional)
    if (afterGcHeap > 0 && afterGcHeap > beforeGcHeap * 0.9 && beforeGcHeap > 0) {
      e.getPresentation().setText("Analyze Memory Leak (GC Ineffective)");
      e.getPresentation().setDescription("Heap didn't decrease after GC - likely a memory leak");
    } else {
      e.getPresentation().setText("Analyze Memory After GC");
      e.getPresentation().setDescription("Analyze what objects survived garbage collection");
    }
  }
}
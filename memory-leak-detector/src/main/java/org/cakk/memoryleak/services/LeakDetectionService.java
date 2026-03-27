package org.cakk.memoryleak.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiElement;
import org.cakk.memoryleak.analysis.AllocationStackAnalyzer;
import org.cakk.memoryleak.settings.MemoryLeakSettings;
import org.cakk.memoryleak.ui.GcConfirmationDialog;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service(Service.Level.PROJECT)
public final class LeakDetectionService {

  // Fix 1: Use getInstance() with the class
  private static final Logger LOG = Logger.getInstance(LeakDetectionService.class);

  // Alternative if the above doesn't work:
  // private static final Logger LOG = Logger.getInstance("#org.cakk.memoryleak.services.LeakDetectionService");

  private final Project project;
  private final MemoryMonitorService monitorService;
  private final AllocationStackAnalyzer stackAnalyzer;
  private final AtomicBoolean showingDialog = new AtomicBoolean(false);

  public LeakDetectionService(Project project) {
    this.project = project;
    this.monitorService = project.getService(MemoryMonitorService.class);
    this.stackAnalyzer = new AllocationStackAnalyzer(project);
    setupListener();
  }

  private void setupListener() {
    // Add null check for monitorService
    if (monitorService == null) {
      LOG.error("MemoryMonitorService is null, cannot setup listener");
      return;
    }

    monitorService.addListener(new MemoryMonitorService.MemoryMonitorListener() {
      @Override
      public void onMemoryUpdate(MemoryMonitorService.@NotNull MemorySnapshot snapshot) {
        // Not needed for this service
      }

      @Override
      public void onGcEvent(MemoryMonitorService.@NotNull GcEvent event) {
        // Not needed for this service
      }

      @Override
      public void onLeakDetected(MemoryMonitorService.@NotNull LeakAlert alert) {
        // Check if project is still valid
        if (project.isDisposed()) {
          return;
        }

        // Log leak with location info
        logLeakWithLocation(alert);

        // Optionally show notifications for severe leaks
        if (alert.severity() == MemoryMonitorService.LeakAlert.Severity.CRITICAL) {
          ApplicationManager.getApplication().invokeLater(() -> {
            // Check again on UI thread
            if (project.isDisposed()) {
              return;
            }
            Messages.showWarningDialog(
                    project,
                    alert.message() + getLocationSummary(),
                    "Critical Memory Leak Detected"
            );
          });
        }
      }

      @Override
      public void onRapidGrowthDetected(MemoryMonitorService.@NotNull RapidGrowthAlert alert) {
        // Check if project is still valid
        if (project.isDisposed()) {
          return;
        }

        // Log rapid growth with location info
        logRapidGrowthWithLocation(alert);
        handleRapidGrowthAlert(alert);
      }
    });
  }

  /**
   * Log memory leak with specific file and line information
   */
  private void logLeakWithLocation(MemoryMonitorService.LeakAlert alert) {
    try {
      // Add null check for stackAnalyzer
      if (stackAnalyzer == null) {
        LOG.warn("StackAnalyzer is null, cannot get leak location");
        LOG.warn("Memory leak detected: " + alert.message());
        return;
      }

      // Get top leak suspects
      List<AllocationStackAnalyzer.LeakLocation> suspects =
              stackAnalyzer.getTopLeakSuspects(5);

      if (suspects != null && !suspects.isEmpty()) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("⚠️ MEMORY LEAK DETECTED - Severity: ").append(alert.severity()).append("\n");
        logMessage.append("Message: ").append(alert.message()).append("\n");
        logMessage.append("Top leak locations:\n");

        for (int i = 0; i < Math.min(3, suspects.size()); i++) {
          AllocationStackAnalyzer.LeakLocation loc = suspects.get(i);
          if (loc != null) {
            logMessage.append("  ").append(i + 1).append(". ")
                    .append(loc.className).append(".")
                    .append(loc.methodName != null ? loc.methodName : "unknown").append("()\n");
            logMessage.append("     File: ").append(loc.fileName != null ? loc.fileName : "unknown")
                    .append(":").append(loc.lineNumber).append("\n");
            logMessage.append("     Memory: ").append(formatBytes(loc.totalMemory))
                    .append(" (").append(loc.instanceCount).append(" instances)\n");
          }
        }

        LOG.warn(logMessage.toString());

        // Also log to console with more details for debugging
        if (LOG.isDebugEnabled()) {
          logDetailedLeakInfo(suspects);
        }
      } else {
        LOG.warn("Memory leak detected but no specific location found: " + alert.message());
      }
    } catch (Exception e) {
      LOG.error("Failed to log leak with location", e);
      LOG.warn("Memory leak detected (location unavailable): " + alert.message());
    }
  }

  /**
   * Log rapid growth with file and line information
   */
  private void logRapidGrowthWithLocation(MemoryMonitorService.RapidGrowthAlert alert) {
    try {
      // Add null check for stackAnalyzer
      if (stackAnalyzer == null) {
        LOG.warn("StackAnalyzer is null, cannot get leak location");
        LOG.warn("Rapid growth detected: growth rate = " + alert.growthRate() + "%");
        return;
      }

      List<AllocationStackAnalyzer.LeakLocation> suspects =
              stackAnalyzer.getTopLeakSuspects(3);

      StringBuilder logMessage = new StringBuilder();
      logMessage.append("⚠️ RAPID MEMORY GROWTH DETECTED\n");
      logMessage.append("Growth rate: ").append(String.format("%.2f%%", alert.growthRate())).append("\n");
      logMessage.append("GC Efficiency: ").append(String.format("%.2f%%", alert.gcEfficiency())).append("\n");

      if (suspects != null && !suspects.isEmpty()) {
        logMessage.append("Primary suspect:\n");
        AllocationStackAnalyzer.LeakLocation top = suspects.get(0);
        if (top != null) {
          logMessage.append("  Class: ").append(top.className).append("\n");
          logMessage.append("  Method: ").append(top.methodName != null ? top.methodName : "unknown").append("()\n");
          logMessage.append("  Location: ").append(top.fileName != null ? top.fileName : "unknown")
                  .append(":").append(top.lineNumber).append("\n");
          logMessage.append("  Memory: ").append(formatBytes(top.totalMemory))
                  .append(" (").append(top.instanceCount).append(" instances)\n");
        }

        if (suspects.size() > 1) {
          logMessage.append("Other potential locations:\n");
          for (int i = 1; i < Math.min(3, suspects.size()); i++) {
            AllocationStackAnalyzer.LeakLocation loc = suspects.get(i);
            if (loc != null) {
              logMessage.append("  - ").append(loc.fileName != null ? loc.fileName : "unknown")
                      .append(":").append(loc.lineNumber)
                      .append(" (").append(loc.className).append(".")
                      .append(loc.methodName != null ? loc.methodName : "unknown").append(")\n");
            }
          }
        }
      } else {
        logMessage.append("No specific location identified. Consider using a profiler.");
      }

      LOG.warn(logMessage.toString());

    } catch (Exception e) {
      LOG.error("Failed to log rapid growth with location", e);
      LOG.warn("Rapid growth detected (location unavailable): growth rate = " +
              String.format("%.2f", alert.growthRate()) + "%");
    }
  }

  /**
   * Log detailed information about leak suspects for debugging
   */
  private void logDetailedLeakInfo(List<AllocationStackAnalyzer.LeakLocation> suspects) {
    if (suspects == null) {
      return;
    }

    for (AllocationStackAnalyzer.LeakLocation loc : suspects) {
      if (loc == null) {
        continue;
      }

      LOG.debug("Leak suspect details:");
      LOG.debug("  Class: " + loc.className);
      LOG.debug("  Method: " + loc.methodName);
      LOG.debug("  File: " + loc.fileName + ":" + loc.lineNumber);
      LOG.debug("  Total Memory: " + loc.totalMemory + " bytes");
      LOG.debug("  Instance Count: " + loc.instanceCount);

      // Try to get and log the actual code snippet if possible
      try {
        if (stackAnalyzer != null) {
          PsiElement element = stackAnalyzer.findPsiElement(
                  loc.className, loc.methodName, loc.fileName, loc.lineNumber);
          if (element != null && element.isValid()) {
            String codeSnippet = getCodeSnippet(element);
            LOG.debug("  Code: " + codeSnippet);
          }
        }
      } catch (Exception e) {
        LOG.debug("Could not get code snippet: " + e.getMessage());
      }
    }
  }

  /**
   * Get a code snippet for logging
   */
  private String getCodeSnippet(PsiElement element) {
    try {
      String text = element.getText();
      if (text != null) {
        // Truncate if too long
        if (text.length() > 100) {
          text = text.substring(0, 97) + "...";
        }
        // Remove newlines for single-line log
        return text.replace('\n', ' ').trim();
      }
    } catch (Exception e) {
      // Ignore
    }
    return "[Unable to get code snippet]";
  }

  /**
   * Get summary of leak locations for UI messages
   */
  private String getLocationSummary() {
    try {
      if (stackAnalyzer == null) {
        return "";
      }

      List<AllocationStackAnalyzer.LeakLocation> suspects =
              stackAnalyzer.getTopLeakSuspects(1);

      if (suspects != null && !suspects.isEmpty()) {
        AllocationStackAnalyzer.LeakLocation loc = suspects.get(0);
        if (loc != null) {
          return "\n\nLocation: " + (loc.fileName != null ? loc.fileName : "unknown") + ":" + loc.lineNumber +
                  " (" + loc.className + "." + (loc.methodName != null ? loc.methodName : "unknown") + "())";
        }
      }
    } catch (Exception e) {
      // Ignore
    }
    return "";
  }

  private void handleRapidGrowthAlert(MemoryMonitorService.RapidGrowthAlert alert) {
    // Prevent multiple dialogs from showing simultaneously
    if (showingDialog.compareAndSet(false, true)) {
      // Use invokeLater to ensure UI thread
      ApplicationManager.getApplication().invokeLater(() -> {
        try {
          // Check if project is still valid
          if (project.isDisposed()) {
            return;
          }

          MemoryLeakSettings settings = MemoryLeakSettings.getInstance(project);

          // Add null check for settings
          if (settings == null) {
            LOG.warn("MemoryLeakSettings is null, using defaults");
            // Auto-run GC as fallback
            if (monitorService != null) {
              monitorService.forceGc();
            }
            return;
          }

          // Auto-run GC if configured
          if (settings.isAutoRunGcOnHighRisk()) {
            LOG.info("Auto-running GC due to high risk detection. " +
                    "Location: " + getTopLeakLocationSummary());
            if (monitorService != null) {
              monitorService.forceGc();
            }
            return;
          }

          // Show confirmation dialog if configured
          if (settings.isShowGcConfirmationDialog()) {
            // Enhanced dialog message with location info
            String locationInfo = getTopLeakLocationSummary();

            GcConfirmationDialog dialog = new GcConfirmationDialog(
                    alert,
                    () -> {
                      LOG.info("User confirmed GC execution. Leak location: " + locationInfo);
                      if (monitorService != null) {
                        monitorService.forceGc();
                      }
                    },
                    () -> {
                      LOG.info("User ignored GC suggestion. Leak location: " + locationInfo);
                    }
            );
            dialog.show();
          } else {
            // Just log the alert with location
            String locationInfo = getTopLeakLocationSummary();
            LOG.warn("Rapid growth detected but dialog disabled: growth rate = " +
                    alert.growthRate() + ". Location: " + locationInfo);
          }
        } catch (Exception e) {
          LOG.warn("Error showing GC confirmation dialog", e);
        } finally {
          showingDialog.set(false);
        }
      });
    }
  }

  /**
   * Get summary of top leak location for logging
   */
  private String getTopLeakLocationSummary() {
    try {
      if (stackAnalyzer == null) {
        return "unknown location (analyzer unavailable)";
      }

      List<AllocationStackAnalyzer.LeakLocation> suspects =
              stackAnalyzer.getTopLeakSuspects(1);

      if (suspects != null && !suspects.isEmpty()) {
        AllocationStackAnalyzer.LeakLocation loc = suspects.get(0);
        if (loc != null) {
          return String.format("%s:%d (%s.%s())",
                  loc.fileName != null ? loc.fileName : "unknown",
                  loc.lineNumber,
                  loc.className,
                  loc.methodName != null ? loc.methodName : "unknown");
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not get leak location summary: " + e.getMessage());
    }
    return "unknown location";
  }

  /**
   * Format bytes to human-readable string
   */
  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  /**
   * Public method to manually check for leaks with location info
   */
  public void checkForLeaksWithLocation() {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        // Add null check for stackAnalyzer
        if (stackAnalyzer == null) {
          LOG.warn("StackAnalyzer is null, cannot check for leaks");
          return;
        }

        List<AllocationStackAnalyzer.LeakLocation> leaks =
                stackAnalyzer.getTopLeakSuspects(10);

        if (leaks != null && !leaks.isEmpty()) {
          StringBuilder report = new StringBuilder();
          report.append("Memory Leak Analysis Report\n");
          report.append("===========================\n\n");

          for (AllocationStackAnalyzer.LeakLocation leak : leaks) {
            if (leak != null) {
              report.append("Location: ").append(leak.fileName != null ? leak.fileName : "unknown")
                      .append(":").append(leak.lineNumber).append("\n");
              report.append("Class: ").append(leak.className).append("\n");
              report.append("Method: ").append(leak.methodName != null ? leak.methodName : "unknown").append("()\n");
              report.append("Memory: ").append(formatBytes(leak.totalMemory)).append("\n");
              report.append("Instances: ").append(leak.instanceCount).append("\n");
              report.append("---------------------------\n");
            }
          }

          LOG.warn(report.toString());

          // Optionally show in UI
          ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
              Messages.showInfoMessage(
                      project,
                      "Found " + leaks.size() + " potential memory leaks.\n" +
                              "Check the logs for detailed location information.",
                      "Memory Leak Analysis"
              );
            }
          });
        } else {
          LOG.info("No memory leaks detected in current analysis");
        }
      } catch (Exception e) {
        LOG.error("Error during manual leak check", e);
      }
    });
  }
}
package org.cakk.memoryleak.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.cakk.memoryleak.settings.MemoryLeakSettings;
import org.cakk.memoryleak.ui.GcConfirmationDialog;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

@Service(Service.Level.PROJECT)
public final class LeakDetectionService {

  private static final Logger LOG = Logger.getInstance(LeakDetectionService.class);

  private final Project project;
  private final MemoryMonitorService monitorService;
  private final AtomicBoolean showingDialog = new AtomicBoolean(false);

  public LeakDetectionService(Project project) {
    this.project = project;
    this.monitorService = project.getService(MemoryMonitorService.class);
    setupListener();
  }

  private void setupListener() {
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
        // Optionally show notifications for severe leaks
        if (alert.severity() == MemoryMonitorService.LeakAlert.Severity.CRITICAL) {
          ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showWarningDialog(
                    project,
                    alert.message(),
                    "Critical Memory Leak Detected"
            );
          });
        }
      }

      @Override
      public void onRapidGrowthDetected(MemoryMonitorService.@NotNull RapidGrowthAlert alert) {
        handleRapidGrowthAlert(alert);
      }
    });
  }

// src/main/java/org/cakk/services/LeakDetectionService.java
// Update to handle dialogs safely

  private void handleRapidGrowthAlert(MemoryMonitorService.RapidGrowthAlert alert) {
    // Prevent multiple dialogs from showing simultaneously
    if (showingDialog.compareAndSet(false, true)) {
      // Use invokeLater to ensure UI thread
      ApplicationManager.getApplication().invokeLater(() -> {
        try {
          MemoryLeakSettings settings = MemoryLeakSettings.getInstance(project);

          // Auto-run GC if configured
          if (settings.isAutoRunGcOnHighRisk()) {
            LOG.info("Auto-running GC due to high risk detection");
            monitorService.forceGc();
            return;
          }

          // Show confirmation dialog if configured
          if (settings.isShowGcConfirmationDialog()) {
            GcConfirmationDialog dialog = new GcConfirmationDialog(
                    alert,
                    () -> {
                      LOG.info("User confirmed GC execution");
                      monitorService.forceGc();
                    },
                    () -> {
                      LOG.info("User ignored GC suggestion");
                    }
            );
            dialog.show();
          } else {
            // Just log the alert
            LOG.warn("Rapid growth detected but dialog disabled: growth rate = " + alert.growthRate());
          }
        } catch (Exception e) {
          LOG.warn("Error showing GC confirmation dialog", e);
        } finally {
          showingDialog.set(false);
        }
      });
    }
  }
}
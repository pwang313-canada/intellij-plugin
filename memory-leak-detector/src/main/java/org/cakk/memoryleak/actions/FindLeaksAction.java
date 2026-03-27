package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.cakk.memoryleak.services.AllocationTracker;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class FindLeaksAction extends AnAction {

  private static final Logger LOG = Logger.getInstance(FindLeaksAction.class);

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    MemoryMonitorService monitor = project.getService(MemoryMonitorService.class);

    if (monitor == null) {
      Messages.showErrorDialog(project, "Memory Monitor Service not available", "Error");
      return;
    }

    // Run analysis in background
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        // Force GC to see what survives
        System.gc();
        Thread.sleep(2000);

        // Get allocation suspects
        List<AllocationTracker.AllocationInfo> suspects =
                monitor.getAllocationTracker().getTopSuspects(10);

        if (!suspects.isEmpty()) {
          StringBuilder report = new StringBuilder();
          report.append("\n");
          report.append("╔═══════════════════════════════════════════════════════════════╗\n");
          report.append("║         🔍 MEMORY LEAK DETECTION REPORT 🔍                     ║\n");
          report.append("╚═══════════════════════════════════════════════════════════════╝\n");
          report.append("\n");

          for (int i = 0; i < Math.min(10, suspects.size()); i++) {
            AllocationTracker.AllocationInfo info = suspects.get(i);
            report.append(String.format("%d. 📍 %s:%d\n", i + 1,
                    info.fileName, info.lineNumber));
            report.append(String.format("   %s.%s()\n",
                    info.className, info.methodName));
            report.append(String.format("   Memory: %s (%d allocations)\n",
                    formatBytes(info.totalMemory.get()), info.allocationCount.get()));
            report.append(String.format("   Surviving: %d objects\n", info.getSurvivingObjects()));

            double survivalRate = info.getSurvivalRate();
            if (survivalRate > 50) {
              report.append("   🔴 HIGH RISK: " + String.format("%.1f%%", survivalRate) + " survived GC!\n");
            } else if (survivalRate > 20) {
              report.append("   🟡 MEDIUM RISK: " + String.format("%.1f%%", survivalRate) + " survived GC.\n");
            }
            report.append("\n");
          }

          LOG.warn(report.toString());

          // Show dialog with option to navigate
          ApplicationManager.getApplication().invokeLater(() -> {
            AllocationTracker.AllocationInfo top = suspects.get(0);
            int result = Messages.showYesNoDialog(
                    project,
                    String.format(
                            "⚠️ MEMORY LEAK DETECTED ⚠️\n\n" +
                                    "📍 Location: %s:%d\n" +
                                    "   %s.%s()\n\n" +
                                    "Memory: %s (%d allocations)\n" +
                                    "Surviving: %d objects (%.1f%%)\n\n" +
                                    "Do you want to navigate to the leak location?",
                            top.fileName, top.lineNumber,
                            top.className, top.methodName,
                            formatBytes(top.totalMemory.get()),
                            top.allocationCount.get(),
                            top.getSurvivingObjects(),
                            top.getSurvivalRate()
                    ),
                    "Memory Leak Detected",
                    "Navigate to Code",
                    "Close",
                    Messages.getWarningIcon()
            );

            if (result == Messages.YES) {
              navigateToLeak(project, top);
            }
          });

        } else {
          ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showInfoMessage(
                    project,
                    "No memory leaks detected.\n\n" +
                            "Memory usage patterns appear healthy.",
                    "Memory Leak Analysis"
            );
          });
        }
      } catch (Exception ex) {
        LOG.error("Error during leak detection", ex);
      }
    });
  }

  private void navigateToLeak(Project project, AllocationTracker.AllocationInfo leak) {
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        // Try to find the file in the project
        VirtualFile file = findFileInProject(project, leak.fileName);

        if (file != null) {
          OpenFileDescriptor descriptor = new OpenFileDescriptor(
                  project, file, Math.max(0, leak.lineNumber - 1), 0);
          FileEditorManager.getInstance(project).openTextEditor(descriptor, true);

          Messages.showInfoMessage(
                  project,
                  String.format("Navigated to leak location:\n📍 %s:%d\n\n%s.%s()\n\n" +
                                  "Memory: %s\nSurviving objects: %d",
                          leak.fileName, leak.lineNumber,
                          leak.className, leak.methodName,
                          formatBytes(leak.totalMemory.get()),
                          leak.getSurvivingObjects()),
                  "Leak Location"
          );
        } else {
          Messages.showWarningDialog(
                  project,
                  String.format("File not found: %s\n\nLeak at %s.%s() line %d\n\n" +
                                  "The file may not be in the current project.",
                          leak.fileName, leak.className, leak.methodName, leak.lineNumber),
                  "File Not Found"
          );
        }
      } catch (Exception e) {
        Messages.showErrorDialog(project, "Error navigating: " + e.getMessage(), "Error");
      }
    });
  }

  private VirtualFile findFileInProject(Project project, String fileName) {
    VirtualFile baseDir = project.getBaseDir();
    if (baseDir == null) return null;
    return findFileRecursive(baseDir, fileName);
  }

  private VirtualFile findFileRecursive(VirtualFile dir, String fileName) {
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
  }
}
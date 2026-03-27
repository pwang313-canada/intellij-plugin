package org.cakk.memoryleak.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.cakk.memoryleak.services.MemoryMonitorService;
import org.jetbrains.annotations.NotNull;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

public class LocalLeakTestAction extends AnAction {

  private static final List<byte[]> LEAK_HOLDER = new ArrayList<>();
  private static boolean isLeaking = false;
  private static Thread leakThread;

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    // Get the monitor service
    MemoryMonitorService monitor = project.getService(MemoryMonitorService.class);

    if (monitor == null) {
      Messages.showErrorDialog(project, "Memory Monitor Service not available", "Error");
      return;
    }

    if (!isLeaking) {
      // Ask for leak parameters
      String input = Messages.showInputDialog(
              project,
              "How many MB to leak? (10-100)\n" +
                      "This will create a memory leak and show the exact location.",
              "Local Memory Leak Test",
              Messages.getQuestionIcon()
      );

      if (input == null) return;

      try {
        int mb = Integer.parseInt(input);
        mb = Math.min(100, Math.max(10, mb));
        final int leakMB = mb;

        isLeaking = true;

        // Get current stack trace to show where leak will happen
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StackTraceElement caller = stackTrace.length > 2 ? stackTrace[2] : null;

        String leakLocation = String.format("%s:%d - %s.%s()",
                caller != null ? caller.getFileName() : "unknown",
                caller != null ? caller.getLineNumber() : 0,
                caller != null ? caller.getClassName() : "unknown",
                caller != null ? caller.getMethodName() : "unknown"
        );

        Messages.showInfoMessage(
                project,
                String.format(
                        "⚠️ Starting memory leak test at:\n" +
                                "📍 %s\n\n" +
                                "Will leak %d MB in 1 MB increments.\n" +
                                "Watch the Memory Monitor for detection.",
                        leakLocation,
                        leakMB
                ),
                "Memory Leak Test Starting"
        );

        // Start leaking in background thread
        leakThread = new Thread(() -> {
          try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage beforeHeap = memoryBean.getHeapMemoryUsage();

            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("📍 MEMORY LEAK TEST STARTED");
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("Leak Location: " + leakLocation);
            System.out.println("Initial Heap: " + formatBytes(beforeHeap.getUsed()));
            System.out.println();

            for (int i = 0; i < leakMB; i++) {
              if (!isLeaking) break;

              // THIS IS THE LEAK - allocating and storing in static list
              byte[] leakedData = new byte[1024 * 1024]; // 1 MB
              LEAK_HOLDER.add(leakedData);

              // Get the exact stack trace of this allocation
              StackTraceElement[] allocationStack = Thread.currentThread().getStackTrace();
              StackTraceElement allocationSite = allocationStack.length > 2 ? allocationStack[2] : null;

              // Track this allocation with the monitor
              if (monitor != null && monitor.getAllocationTracker() != null) {
                monitor.getAllocationTracker().trackAllocationWithStack(
                        allocationSite != null ? allocationSite.getClassName() : "unknown",
                        allocationSite != null ? allocationSite.getMethodName() : "unknown",
                        allocationSite != null ? allocationSite.getFileName() : "unknown",
                        allocationSite != null ? allocationSite.getLineNumber() : 0,
                        1024 * 1024, // 1 MB
                        getStackTraceString(allocationStack),
                        allocationStack
                );
              }

              if ((i + 1) % 10 == 0 || i == leakMB - 1) {
                MemoryUsage currentHeap = memoryBean.getHeapMemoryUsage();
                System.out.printf("💾 Leaked %d MB | Total: %d MB | Heap: %s\n",
                        i + 1, LEAK_HOLDER.size(),
                        formatBytes(currentHeap.getUsed()));
                System.out.printf("   📍 Allocation at: %s.%s(%s:%d)\n",
                        allocationSite != null ? allocationSite.getClassName() : "unknown",
                        allocationSite != null ? allocationSite.getMethodName() : "unknown",
                        allocationSite != null ? allocationSite.getFileName() : "unknown",
                        allocationSite != null ? allocationSite.getLineNumber() : 0);
              }

              Thread.sleep(100);
            }

            MemoryUsage afterHeap = memoryBean.getHeapMemoryUsage();
            long leakedMemory = afterHeap.getUsed() - beforeHeap.getUsed();

            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("⚠️ MEMORY LEAK TEST COMPLETE");
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("Leaked: " + formatBytes(leakedMemory) + " (" + LEAK_HOLDER.size() + " MB)");
            System.out.println("Final Heap: " + formatBytes(afterHeap.getUsed()));
            System.out.println("Leak Location: " + leakLocation);
            System.out.println();
            System.out.println("💡 To detect this leak, click 'Find Memory Leaks'");
            System.out.println("   The leak will be reported at: " + leakLocation);

            ApplicationManager.getApplication().invokeLater(() -> {
              int result = Messages.showYesNoDialog(
                      project,
                      String.format(
                              "⚠️ MEMORY LEAK TEST COMPLETE ⚠️\n\n" +
                                      "Leaked: %s (%d MB)\n" +
                                      "Final Heap: %s\n\n" +
                                      "Leak Location:\n" +
                                      "📍 %s\n\n" +
                                      "The memory is still held in memory.\n\n" +
                                      "Do you want to analyze the leak location?",
                              formatBytes(leakedMemory),
                              LEAK_HOLDER.size(),
                              formatBytes(afterHeap.getUsed()),
                              leakLocation
                      ),
                      "Memory Leak Test Complete",
                      "Analyze Now",
                      "Close",
                      Messages.getWarningIcon()
              );

              if (result == Messages.YES) {
                // Navigate to the leak location
                navigateToLeakLocation(project, caller);
              }
            });

          } catch (Exception ex) {
            ex.printStackTrace();
          }
        });
        leakThread.start();

      } catch (NumberFormatException ex) {
        Messages.showErrorDialog(project, "Invalid number", "Error");
      }
    } else {
      // Stop leaking
      isLeaking = false;
      if (leakThread != null) {
        leakThread.interrupt();
      }

      MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
      MemoryUsage currentHeap = memoryBean.getHeapMemoryUsage();

      Messages.showInfoMessage(
              project,
              String.format(
                      "Leak stopped.\n" +
                              "Leaked: %d MB\n" +
                              "Current Heap: %s\n\n" +
                              "Click 'Find Memory Leaks' to analyze what's still held.",
                      LEAK_HOLDER.size(),
                      formatBytes(currentHeap.getUsed())
              ),
              "Leak Stopped"
      );
    }
  }

  private String getStackTraceString(StackTraceElement[] stackTrace) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < Math.min(20, stackTrace.length); i++) {
      sb.append(stackTrace[i].toString()).append("\n");
    }
    return sb.toString();
  }

  private void navigateToLeakLocation(Project project, StackTraceElement location) {
    if (location == null) return;

    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        String fileName = location.getFileName();
        int lineNumber = location.getLineNumber();

        if (fileName != null && lineNumber > 0) {
          // Try to find the file in the project
          VirtualFile file = findFileInProject(project, fileName);

          if (file != null) {
            OpenFileDescriptor descriptor = new OpenFileDescriptor(
                    project, file, Math.max(0, lineNumber - 1), 0);
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true);

            Messages.showInfoMessage(
                    project,
                    String.format("Navigated to leak location:\n📍 %s:%d\n\n%s.%s()",
                            fileName, lineNumber,
                            location.getClassName(),
                            location.getMethodName()),
                    "Leak Location"
            );
          } else {
            Messages.showWarningDialog(
                    project,
                    String.format("File not found in project: %s\n\nLeak location: %s:%d",
                            fileName, location.getClassName(), lineNumber),
                    "File Not Found"
            );
          }
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
    if (project == null) {
      e.getPresentation().setEnabled(false);
      return;
    }

    if (isLeaking) {
      e.getPresentation().setText("Stop Local Leak Test");
      e.getPresentation().setDescription("Stop the running memory leak test");
    } else {
      e.getPresentation().setText("Test Local Memory Leak");
      e.getPresentation().setDescription("Test memory leak detection with exact file and line tracking");
    }
  }
}
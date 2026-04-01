package org.cakk.threadlock.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.cakk.threadlock.ui.ThreadLockToolWindowFactory;
import org.cakk.threadlock.ui.ThreadLockToolWindowPanel;
import org.jetbrains.annotations.NotNull;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RuntimeThreadlockMonitorService {
  private static final Logger LOG = Logger.getInstance(RuntimeThreadlockMonitorService.class);
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private final Project project;
  private volatile boolean monitoring = false;

  public RuntimeThreadlockMonitorService(@NotNull Project project) {
    this.project = project;
  }

  public void startMonitoring() {
    if (monitoring) return;
    monitoring = true;
    executor.scheduleAtFixedRate(this::checkForDeadlocks, 0, 5, TimeUnit.SECONDS);
    updateStatus("Monitoring started (check every 5s)");
  }

  public void stopMonitoring() {
    monitoring = false;
    executor.shutdown();
    updateStatus("Monitoring stopped");
  }

  public boolean isMonitoring() {
    return monitoring;
  }

  private void checkForDeadlocks() {
    if (!monitoring) return;

    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    long[] deadlockedThreads = threadBean.findDeadlockedThreads();

    if (deadlockedThreads != null && deadlockedThreads.length > 0) {
      ThreadInfo[] infos = threadBean.getThreadInfo(deadlockedThreads, true, true);
      StringBuilder sb = new StringBuilder();
      for (ThreadInfo info : infos) {
        sb.append("Thread: ").append(info.getThreadName()).append("\n");
        for (StackTraceElement element : info.getStackTrace()) {
          sb.append("    at ").append(element).append("\n");
        }
        sb.append("\n");
      }
      String message = sb.toString();
      LOG.warn("Deadlock detected!\n" + message);
      showDeadlockNotification(message);
    }
  }

  private void showDeadlockNotification(String details) {
    ApplicationManager.getApplication().invokeLater(() -> {
      // System notification
      com.intellij.notification.NotificationGroupManager.getInstance()
              .getNotificationGroup("Deadlock Detector")
              .createNotification("Deadlock Detected", details,
                      com.intellij.notification.NotificationType.WARNING)
              .notify(project);

      // Update tool window panel
      ThreadLockToolWindowPanel panel = project.getUserData(org.cakk.threadlock.ui.ThreadLockToolWindowFactory.KEY);
      if (panel != null) {
        panel.addDeadlockInfo(details);
      }
    });
  }

  private void updateStatus(String status) {
    ApplicationManager.getApplication().invokeLater(() -> {
      ThreadLockToolWindowPanel panel = project.getUserData(ThreadLockToolWindowFactory.KEY);
      if (panel != null) {
        panel.setMonitorStatus(status);
      }
    });
  }
}
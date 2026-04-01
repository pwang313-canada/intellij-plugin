package org.cakk.threadlock.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import org.cakk.threadlock.ui.ThreadLockToolWindowPanel;
import org.cakk.threadlock.ui.ThreadLockToolWindowFactory;

public class RemoteDeadlockMonitorService {
  private static final Logger LOG = Logger.getInstance(RemoteDeadlockMonitorService.class);

  // Non‑final to allow recreation after shutdown
  private ScheduledExecutorService executor;
  private final Project project;
  private volatile boolean monitoring = false;
  private JMXConnector jmxConnector;
  private ThreadMXBean remoteBean;

  public RemoteDeadlockMonitorService(Project project) {
    this.project = project;
    this.executor = Executors.newSingleThreadScheduledExecutor();
  }

  public boolean connect(String pid) {
    VirtualMachine vm = null;
    try {
      vm = VirtualMachine.attach(pid);
      String connectorAddress = vm.getAgentProperties()
              .getProperty("com.sun.management.jmxremote.localConnectorAddress");
      if (connectorAddress == null) {
        vm.startLocalManagementAgent();
        connectorAddress = vm.getAgentProperties()
                .getProperty("com.sun.management.jmxremote.localConnectorAddress");
        if (connectorAddress == null) {
          throw new RuntimeException("Failed to start JMX agent");
        }
      }
      vm.detach();
      vm = null;

      JMXServiceURL url = new JMXServiceURL(connectorAddress);
      jmxConnector = JMXConnectorFactory.connect(url);
      MBeanServerConnection mbsc = jmxConnector.getMBeanServerConnection();
      remoteBean = ManagementFactory.newPlatformMXBeanProxy(mbsc,
              ManagementFactory.THREAD_MXBEAN_NAME,
              ThreadMXBean.class);
      return true;
    } catch (Exception e) {
      LOG.error("Failed to connect to process " + pid, e);
      String errorMsg = "Connection failed: " + e.getMessage();
      if (e.getMessage() != null && e.getMessage().contains("Access is denied")) {
        errorMsg = "Access denied – try running IntelliJ and the target JVM under the same user account.";
      }
      updateStatus(errorMsg);
      return false;
    } finally {
      if (vm != null) {
        try {
          vm.detach();
        } catch (Exception ignored) {}
      }
    }
  }

  public void disconnect() {
    monitoring = false;
    if (jmxConnector != null) {
      try {
        jmxConnector.close();
      } catch (Exception e) {
        LOG.warn("Error closing JMX connection", e);
      }
      jmxConnector = null;
    }
    remoteBean = null;

    // Shut down the executor and create a fresh one
    if (executor != null && !executor.isShutdown()) {
      executor.shutdownNow();
    }
    // Create a new executor for future use
    executor = Executors.newSingleThreadScheduledExecutor();

    updateStatus("Disconnected");
  }

  public void startMonitoring() {
    if (monitoring) return;
    if (remoteBean == null) {
      updateStatus("Not connected to any process");
      return;
    }

    // Ensure executor is alive (it might have been shut down)
    if (executor == null || executor.isShutdown()) {
      executor = Executors.newSingleThreadScheduledExecutor();
    }

    monitoring = true;
    executor.scheduleAtFixedRate(this::checkForDeadlocks, 0, 5, TimeUnit.SECONDS);
    updateStatus("Monitoring started (check every 5s)");
  }

  public void stopMonitoring() {
    monitoring = false;
    if (executor != null && !executor.isShutdown()) {
      executor.shutdown();
    }
    updateStatus("Monitoring stopped");
  }

  public boolean isMonitoring() {
    return monitoring;
  }

  private void checkForDeadlocks() {
    if (!monitoring || remoteBean == null) return;
    try {
      long[] deadlocked = remoteBean.findDeadlockedThreads();
      if (deadlocked != null && deadlocked.length > 0) {
        ThreadInfo[] infos = remoteBean.getThreadInfo(deadlocked, true, true);
        StringBuilder sb = new StringBuilder();
        for (ThreadInfo info : infos) {
          sb.append("Thread: ").append(info.getThreadName()).append("\n");
          for (StackTraceElement element : info.getStackTrace()) {
            sb.append("    at ");
            // Class name + method name
            sb.append(element.getClassName())
                    .append(".")
                    .append(element.getMethodName());
            // File name and line number
            String fileName = element.getFileName();
            int lineNumber = element.getLineNumber();
            if (fileName != null && lineNumber >= 0) {
              sb.append(" (").append(fileName).append(":").append(lineNumber).append(")");
            } else if (fileName != null) {
              sb.append(" (").append(fileName).append(")");
            } else if (lineNumber >= 0) {
              sb.append(" (line ").append(lineNumber).append(")");
            }
            sb.append("\n");
          }
          sb.append("\n");
        }
        notifyDeadlock(sb.toString());
      }
    } catch (Exception e) {
      LOG.warn("Error during deadlock check", e);
    }
  }

  private void notifyDeadlock(String details) {
    ApplicationManager.getApplication().invokeLater(() -> {
      com.intellij.notification.NotificationGroupManager.getInstance()
              .getNotificationGroup("Deadlock Detector")
              .createNotification("Deadlock Detected", details,
                      com.intellij.notification.NotificationType.WARNING)
              .notify(project);

      ThreadLockToolWindowPanel panel = project.getUserData(ThreadLockToolWindowFactory.KEY);
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

  public static List<String> listRunningJavaProcesses() {
    List<String> pids = new ArrayList<>();
    for (VirtualMachineDescriptor vmd : VirtualMachine.list()) {
      pids.add(vmd.id() + " - " + vmd.displayName());
    }
    return pids;
  }
}
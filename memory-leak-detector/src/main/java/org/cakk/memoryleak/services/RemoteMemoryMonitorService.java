// src/main/java/org/cakk/memoryleak/services/RemoteMemoryMonitorService.java
package org.cakk.memoryleak.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service(Service.Level.PROJECT)
public final class RemoteMemoryMonitorService {

  private static final Logger LOG = Logger.getInstance(RemoteMemoryMonitorService.class);

  private final Project project;
  private MBeanServerConnection mbsc;
  private MemoryMXBean memoryBean;
  private List<MemoryPoolMXBean> memoryPools;
  private String connectedPid;
  private JMXConnector connector;
  private final AtomicBoolean isConnected = new AtomicBoolean(false);
  private final List<RemoteMemoryListener> listeners = new CopyOnWriteArrayList<>();

  // Data classes
  public record RemoteMemorySnapshot(
          long timestamp,
          long heapUsed,
          long heapCommitted,
          long heapMax,
          long oldGenUsed,
          long oldGenCommitted,
          long youngGenUsed,
          long metaspaceUsed
  ) {}

  public record JavaProcess(String pid, String displayName, String mainClass, boolean jmxEnabled) {
    @Override
    public String toString() {
      String status = jmxEnabled ? "✅ JMX Ready" : "❌ JMX Not Enabled";
      return String.format("%s - %s (PID: %s) %s", mainClass, displayName, pid, status);
    }
  }

  public record GcEvent(
          long timestamp,
          String gcName,
          long duration,
          long oldGenBefore,
          long oldGenAfter,
          long oldGenReclaimed
  ) {}

  public interface RemoteMemoryListener {
    void onMemoryUpdate(RemoteMemorySnapshot snapshot);
    void onGcEvent(GcEvent event);
    void onConnectionStatusChanged(boolean connected, String pid);
    void onError(String error);
  }

  public RemoteMemoryMonitorService(Project project) {
    this.project = project;
  }

  /**
   * List all running Java processes
   */
  public List<JavaProcess> listJavaProcesses() {
    List<JavaProcess> processes = new ArrayList<>();
    List<VirtualMachineDescriptor> vms = VirtualMachine.list();

    for (VirtualMachineDescriptor vm : vms) {
      try {
        String pid = vm.id();
        String displayName = vm.displayName();
        String mainClass = extractMainClass(displayName);

        // Skip the IDE itself
        if (!displayName.contains("idea") &&
                !displayName.contains("ij") &&
                !displayName.contains("jbr") &&
                !displayName.contains("jdk")) {

          boolean jmxEnabled = checkJMXEnabled(pid);
          processes.add(new JavaProcess(pid, displayName, mainClass, jmxEnabled));
        }
      } catch (Exception e) {
        LOG.warn("Error listing process", e);
      }
    }

    processes.sort(Comparator.comparing(JavaProcess::pid));
    return processes;
  }

  /**
   * Connect to a running Java process
   */
  public boolean connectToProcess(String pid) throws Exception {
    LOG.info("Attempting to connect to process: " + pid);

    // Try JMX port connection
    if (tryConnectViaPort(9020)) {
      LOG.info("Successfully connected via JMX port 9020");
      connectedPid = pid;
      isConnected.set(true);
      notifyConnectionStatusChanged(true, pid);
      return true;
    }

    // Try attach API
    if (tryConnectViaAttach(pid)) {
      LOG.info("Successfully connected via attach API");
      connectedPid = pid;
      isConnected.set(true);
      notifyConnectionStatusChanged(true, pid);
      return true;
    }

    LOG.error("Failed to connect to process: " + pid);
    throw new Exception("Cannot connect. Make sure JMX is enabled with -Dcom.sun.management.jmxremote.port=9020");
  }

  /**
   * Try to connect via specific JMX port
   */
  private boolean tryConnectViaPort(int port) {
    try {
      String jmxUrl = String.format(
              "service:jmx:rmi:///jndi/rmi://localhost:%d/jmxrmi", port);
      JMXServiceURL url = new JMXServiceURL(jmxUrl);

      Map<String, Object> env = new HashMap<>();
      env.put("jmx.remote.x.request.timeout", 5000);

      JMXConnector connector = JMXConnectorFactory.connect(url, env);
      MBeanServerConnection mbsc = connector.getMBeanServerConnection();

      // Test connection
      mbsc.getDefaultDomain();

      this.connector = connector;
      this.mbsc = mbsc;
      initMBeans();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Try to connect via attach API
   */
  private boolean tryConnectViaAttach(String pid) {
    VirtualMachine vm = null;
    try {
      vm = VirtualMachine.attach(pid);

      String jmxUrl = vm.getAgentProperties().getProperty(
              "com.sun.management.jmxremote.localConnectorAddress");

      if (jmxUrl != null) {
        JMXServiceURL url = new JMXServiceURL(jmxUrl);
        this.connector = JMXConnectorFactory.connect(url);
        this.mbsc = connector.getMBeanServerConnection();
        initMBeans();
        return true;
      }
    } catch (Exception e) {
      LOG.warn("Attach failed: " + e.getMessage());
    } finally {
      if (vm != null) {
        try {
          vm.detach();
        } catch (Exception e) {
          // Ignore
        }
      }
    }
    return false;
  }

  /**
   * Check if JMX is enabled on a process
   */
  private boolean checkJMXEnabled(String pid) {
    int[] ports = {9020, 9090, 9999, 1099};
    for (int port : ports) {
      try {
        String jmxUrl = String.format(
                "service:jmx:rmi:///jndi/rmi://localhost:%d/jmxrmi", port);
        JMXServiceURL url = new JMXServiceURL(jmxUrl);
        JMXConnector connector = JMXConnectorFactory.connect(url);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();
        mbsc.getDefaultDomain();
        connector.close();
        return true;
      } catch (Exception e) {
        // Port not available
      }
    }
    return false;
  }

  /**
   * Initialize MBeans after connection
   */
  private void initMBeans() throws Exception {
    memoryBean = ManagementFactory.newPlatformMXBeanProxy(
            mbsc,
            ManagementFactory.MEMORY_MXBEAN_NAME,
            MemoryMXBean.class
    );

    memoryPools = new ArrayList<>();
    Set<ObjectName> poolNames = mbsc.queryNames(
            new ObjectName("java.lang:type=MemoryPool,*"), null);
    for (ObjectName name : poolNames) {
      MemoryPoolMXBean pool = ManagementFactory.newPlatformMXBeanProxy(
              mbsc,
              name.getCanonicalName(),
              MemoryPoolMXBean.class
      );
      memoryPools.add(pool);
    }

    // Setup GC listener for remote JVM
    setupRemoteGcListener();
  }

  /**
   * Setup GC listener for remote JVM
   */
  private void setupRemoteGcListener() throws Exception {
    ObjectName gcName = new ObjectName("java.lang:type=GarbageCollector,*");
    Set<ObjectName> gcBeans = mbsc.queryNames(gcName, null);

    for (ObjectName name : gcBeans) {
      mbsc.addNotificationListener(name, (notification, handback) -> {
        if (notification.getType().equals("com.sun.management.gc.notification")) {
          analyzeRemoteGcEvent(notification);
        }
      }, null, null);
    }
  }

  /**
   * Analyze GC event from remote JVM
   */
  private void analyzeRemoteGcEvent(Notification notification) {
    try {
      CompositeData cd = (CompositeData) notification.getUserData();
      GarbageCollectionNotificationInfo info =
              GarbageCollectionNotificationInfo.from(cd);

      GcInfo gcInfo = info.getGcInfo();
      Map<String, MemoryUsage> beforeMap = gcInfo.getMemoryUsageBeforeGc();
      Map<String, MemoryUsage> afterMap = gcInfo.getMemoryUsageAfterGc();

      long oldGenBefore = 0;
      long oldGenAfter = 0;

      for (Map.Entry<String, MemoryUsage> entry : beforeMap.entrySet()) {
        String poolName = entry.getKey();
        if (isOldGenPool(poolName)) {
          MemoryUsage before = entry.getValue();
          MemoryUsage after = afterMap.get(poolName);
          if (after != null) {
            oldGenBefore = before.getUsed();
            oldGenAfter = after.getUsed();
            break;
          }
        }
      }

      GcEvent event = new GcEvent(
              System.currentTimeMillis(),
              info.getGcName(),
              gcInfo.getDuration(),
              oldGenBefore,
              oldGenAfter,
              oldGenBefore - oldGenAfter
      );

      notifyGcEvent(event);

    } catch (Exception e) {
      LOG.warn("Error analyzing remote GC event", e);
    }
  }

  /**
   * Get current memory snapshot from remote JVM
   */
  public RemoteMemorySnapshot getCurrentSnapshot() {
    if (mbsc == null) {
      return null;
    }

    try {
      MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
      MemoryUsage oldGenUsage = getOldGenUsage();
      MemoryUsage youngGenUsage = getYoungGenUsage();
      MemoryUsage metaspaceUsage = getMetaspaceUsage();

      return new RemoteMemorySnapshot(
              System.currentTimeMillis(),
              heapUsage.getUsed(),
              heapUsage.getCommitted(),
              heapUsage.getMax(),
              oldGenUsage != null ? oldGenUsage.getUsed() : 0,
              oldGenUsage != null ? oldGenUsage.getCommitted() : 0,
              youngGenUsage != null ? youngGenUsage.getUsed() : 0,
              metaspaceUsage != null ? metaspaceUsage.getUsed() : 0
      );
    } catch (Exception e) {
      LOG.warn("Error getting remote snapshot", e);
      return null;
    }
  }

  /**
   * Force GC on remote JVM
   */
  public void forceRemoteGc() {
    try {
      memoryBean.gc();
      LOG.info("Remote GC triggered on process " + connectedPid);
      notifyError("Remote GC triggered");
    } catch (Exception e) {
      LOG.warn("Error triggering remote GC", e);
      notifyError("Failed to trigger remote GC: " + e.getMessage());
    }
  }

  /**
   * Disconnect from remote JVM
   */
  public void disconnect() {
    try {
      if (connector != null) {
        connector.close();
      }
    } catch (Exception e) {
      LOG.warn("Error disconnecting", e);
    }

    mbsc = null;
    memoryBean = null;
    memoryPools = null;
    connectedPid = null;
    isConnected.set(false);

    notifyConnectionStatusChanged(false, null);
    LOG.info("Disconnected from remote process");
  }

  /**
   * Check if connected to remote JVM
   */
  public boolean isConnected() {
    return isConnected.get();
  }

  /**
   * Get connected PID
   */
  public String getConnectedPid() {
    return connectedPid;
  }

  /**
   * Add listener
   */
  public void addListener(RemoteMemoryListener listener) {
    listeners.add(listener);
  }

  /**
   * Remove listener
   */
  public void removeListener(RemoteMemoryListener listener) {
    listeners.remove(listener);
  }

  // Private helper methods

  private MemoryUsage getOldGenUsage() {
    for (MemoryPoolMXBean pool : memoryPools) {
      String name = pool.getName();
      if (name.contains("Tenured") || name.contains("Old") ||
              name.equals("PS Old Gen") || name.equals("G1 Old Gen")) {
        return pool.getUsage();
      }
    }
    return null;
  }

  private MemoryUsage getYoungGenUsage() {
    for (MemoryPoolMXBean pool : memoryPools) {
      String name = pool.getName();
      if (name.contains("Eden") || name.equals("PS Eden Space") ||
              name.equals("G1 Eden Space")) {
        return pool.getUsage();
      }
    }
    return null;
  }

  private MemoryUsage getMetaspaceUsage() {
    for (MemoryPoolMXBean pool : memoryPools) {
      String name = pool.getName();
      if (name.contains("Metaspace") || name.equals("Compressed Class Space")) {
        return pool.getUsage();
      }
    }
    return null;
  }

  private boolean isOldGenPool(String poolName) {
    return poolName.contains("Tenured") || poolName.contains("Old") ||
            poolName.equals("PS Old Gen") || poolName.equals("G1 Old Gen");
  }

  private String extractMainClass(String displayName) {
    if (displayName == null) return "Unknown";
    String[] parts = displayName.split("\\s+");
    for (String part : parts) {
      if (part.contains(".") && !part.contains("=")) {
        return part;
      }
    }
    return displayName;
  }

  public String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  // Notification methods

  private void notifyMemoryUpdate(RemoteMemorySnapshot snapshot) {
    for (RemoteMemoryListener listener : listeners) {
      try {
        listener.onMemoryUpdate(snapshot);
      } catch (Exception e) {
        LOG.warn("Error notifying listener", e);
      }
    }
  }

  private void notifyGcEvent(GcEvent event) {
    for (RemoteMemoryListener listener : listeners) {
      try {
        listener.onGcEvent(event);
      } catch (Exception e) {
        LOG.warn("Error notifying listener", e);
      }
    }
  }

  private void notifyConnectionStatusChanged(boolean connected, String pid) {
    for (RemoteMemoryListener listener : listeners) {
      try {
        listener.onConnectionStatusChanged(connected, pid);
      } catch (Exception e) {
        LOG.warn("Error notifying listener", e);
      }
    }
  }

  private void notifyError(String error) {
    for (RemoteMemoryListener listener : listeners) {
      try {
        listener.onError(error);
      } catch (Exception e) {
        LOG.warn("Error notifying listener", e);
      }
    }
  }
}
package org.cakk.memoryleak.core;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import javax.management.*;
import javax.management.openmbean.CompositeData;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service(Service.Level.PROJECT)
public final class MemoryLeakMonitorService {
  private static final Logger LOG = Logger.getInstance(MemoryLeakMonitorService.class);

  private final Project project;
  private final MemoryMXBean memoryBean;
  private final List<MemoryPoolMXBean> memoryPools;
  private final List<GcEvent> gcEvents = new CopyOnWriteArrayList<>();
  private final List<Double> oldGenGrowthRates = new ArrayList<>();
  private final List<MemoryLeakListener> listeners = new CopyOnWriteArrayList<>();

  private ScheduledExecutorService scheduler;
  private AtomicBoolean isMonitoring = new AtomicBoolean(false);
  private long baselineUsedHeap = 0;
  private long baselineOldGen = 0;
  private long lastAlertTime = 0;
  private static final long ALERT_COOLDOWN_MS = 60000; // 1 minute

  public MemoryLeakMonitorService(Project project) {
    this.project = project;
    this.memoryBean = ManagementFactory.getMemoryMXBean();
    this.memoryPools = ManagementFactory.getMemoryPoolMXBeans();
  }

  public void addListener(MemoryLeakListener listener) {
    listeners.add(listener);
  }

  public void removeListener(MemoryLeakListener listener) {
    listeners.remove(listener);
  }

  public void startMonitoring() {
    if (isMonitoring.getAndSet(true)) {
      return;
    }

    notifyListeners(l -> l.onMonitoringStarted());

    // Establish baseline
    establishBaseline();

    // Setup GC listener
    setupGcListener();

    // Start periodic monitoring
    startTrendMonitoring();

    LOG.info("Memory leak monitoring started for project: " + project.getName());
  }

  public void stopMonitoring() {
    if (!isMonitoring.getAndSet(false)) {
      return;
    }

    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    notifyListeners(l -> l.onMonitoringStopped());
    LOG.info("Memory leak monitoring stopped for project: " + project.getName());
  }

  public boolean isMonitoring() {
    return isMonitoring.get();
  }

  public MemoryLeakStats getCurrentStats() {
    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    MemoryUsage oldGenUsage = getOldGenUsage();

    MemoryLeakStats stats = new MemoryLeakStats();
    stats.heapUsed = heapUsage.getUsed();
    stats.heapCommitted = heapUsage.getCommitted();
    stats.heapMax = heapUsage.getMax();

    if (oldGenUsage != null) {
      stats.oldGenUsed = oldGenUsage.getUsed();
      stats.oldGenCommitted = oldGenUsage.getCommitted();
      stats.oldGenMax = oldGenUsage.getMax();
    }

    stats.gcEfficiency = calculateGcEfficiency();
    stats.growthRate = calculateGrowthRate();
    stats.gcCount = gcEvents.size();

    return stats;
  }

  public void forceGc() {
    notifyListeners(l -> l.onForceGcStarted());

    CompletableFuture.runAsync(() -> {
      long beforeOldGen = getOldGenUsed();

      System.gc();

      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      long afterOldGen = getOldGenUsed();
      long recovered = beforeOldGen - afterOldGen;

      notifyListeners(l -> l.onForceGcCompleted(recovered));
    });
  }

  private long getOldGenUsed() {
    MemoryUsage usage = getOldGenUsage();
    return usage != null ? usage.getUsed() : 0;
  }

  private void establishBaseline() {
    // Force multiple GC cycles
    for (int i = 0; i < 3; i++) {
      System.gc();
      try { Thread.sleep(1000); } catch (InterruptedException e) {}
    }

    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    MemoryUsage oldGenUsage = getOldGenUsage();

    baselineUsedHeap = heapUsage.getUsed();
    baselineOldGen = oldGenUsage != null ? oldGenUsage.getUsed() : 0;

    notifyListeners(l -> l.onBaselineEstablished(baselineUsedHeap, baselineOldGen));
  }

  private void setupGcListener() {
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    for (GarbageCollectorMXBean gcBean : gcBeans) {
      if (gcBean instanceof NotificationEmitter) {
        NotificationEmitter emitter = (NotificationEmitter) gcBean;

        emitter.addNotificationListener((notification, handback) -> {
          if (notification.getType().equals(
                  GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {

            CompositeData cd = (CompositeData) notification.getUserData();
            GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from(cd);

            analyzeGcEvent(info);
          }
        }, null, null);
      }
    }
  }

  private void analyzeGcEvent(GarbageCollectionNotificationInfo info) {
    GcInfo gcInfo = info.getGcInfo();

    Map<String, MemoryUsage> beforeMap = gcInfo.getMemoryUsageBeforeGc();
    Map<String, MemoryUsage> afterMap = gcInfo.getMemoryUsageAfterGc();

    GcEvent event = new GcEvent();
    event.timestamp = System.currentTimeMillis();
    event.gcName = info.getGcName();
    event.duration = gcInfo.getDuration();

    for (Map.Entry<String, MemoryUsage> entry : beforeMap.entrySet()) {
      if (isOldGenPool(entry.getKey())) {
        MemoryUsage before = entry.getValue();
        MemoryUsage after = afterMap.get(entry.getKey());

        if (after != null) {
          event.oldGenBefore = before.getUsed();
          event.oldGenAfter = after.getUsed();
          event.oldGenReclaimed = before.getUsed() - after.getUsed();
          break;
        }
      }
    }

    gcEvents.add(event);

    while (gcEvents.size() > 100) {
      gcEvents.remove(0);
    }

    notifyListeners(l -> l.onGcEvent(event));
    detectLeakPatterns();
  }

  private void startTrendMonitoring() {
    scheduler = Executors.newScheduledThreadPool(1);

    scheduler.scheduleAtFixedRate(() -> {
      if (!isMonitoring.get()) return;

      MemoryLeakStats stats = getCurrentStats();

      // Calculate growth percentages
      double heapGrowthPercent = baselineUsedHeap > 0 ?
              ((stats.heapUsed - baselineUsedHeap) * 100.0) / baselineUsedHeap : 0;
      double oldGenGrowthPercent = baselineOldGen > 0 ?
              ((stats.oldGenUsed - baselineOldGen) * 100.0) / baselineOldGen : 0;

      // Update growth rate history
      oldGenGrowthRates.add((double) stats.oldGenUsed);
      if (oldGenGrowthRates.size() > 10) {
        oldGenGrowthRates.remove(0);
      }

      // Calculate recent growth rate
      double recentGrowthRate;
      if (oldGenGrowthRates.size() >= 2) {
        double first = oldGenGrowthRates.get(0);
        double last = oldGenGrowthRates.get(oldGenGrowthRates.size() - 1);
        recentGrowthRate = first > 0 ? ((last - first) / first) * 100 : 0;
      } else {
        recentGrowthRate = 0;
      }

      // Check for leaks
      checkForLeaks(stats, oldGenGrowthPercent, recentGrowthRate);

      // Notify listeners
      notifyListeners(l -> l.onMemoryStatsUpdate(stats, heapGrowthPercent,
              oldGenGrowthPercent, recentGrowthRate));

    }, 5, 3, TimeUnit.SECONDS);
  }

  private void checkForLeaks(MemoryLeakStats stats, double oldGenGrowthPercent, double recentGrowthRate) {
    long now = System.currentTimeMillis();

    if (oldGenGrowthPercent > 50 && stats.gcEfficiency < 10) {
      if (now - lastAlertTime > ALERT_COOLDOWN_MS) {
        notifyListeners(l -> l.onHighRiskLeakDetected(oldGenGrowthPercent, stats.gcEfficiency));
        lastAlertTime = now;
      }
    } else if (oldGenGrowthPercent > 20 && stats.gcEfficiency < 20) {
      if (now - lastAlertTime > ALERT_COOLDOWN_MS) {
        notifyListeners(l -> l.onWarningLeakDetected(oldGenGrowthPercent, stats.gcEfficiency));
        lastAlertTime = now;
      }
    } else if (recentGrowthRate > 15) {
      if (now - lastAlertTime > ALERT_COOLDOWN_MS) {
        notifyListeners(l -> l.onRapidGrowthDetected(recentGrowthRate));
        lastAlertTime = now;
      }
    }
  }

  private void detectLeakPatterns() {
    if (gcEvents.size() < 10) return;

    List<GcEvent> last10 = new ArrayList<>(gcEvents.subList(gcEvents.size() - 10, gcEvents.size()));

    boolean increasing = true;
    Long previous = null;

    for (GcEvent event : last10) {
      if (event.oldGenAfter > 0) {
        if (previous != null && event.oldGenAfter <= previous) {
          increasing = false;
          break;
        }
        previous = event.oldGenAfter;
      }
    }

    if (increasing && previous != null) {
      long firstValue = last10.get(0).oldGenAfter;
      long lastValue = last10.get(last10.size() - 1).oldGenAfter;
      double increase = firstValue > 0 ? ((lastValue - firstValue) * 100.0) / firstValue : 0;

      if (increase > 20) {
        notifyListeners(l -> l.onLeakPatternDetected(increase));
      }
    }
  }

  private double calculateGcEfficiency() {
    if (gcEvents.isEmpty()) return 100.0;

    long totalReclaimed = 0;
    long totalOldGenBefore = 0;

    for (GcEvent event : gcEvents) {
      if (event.oldGenReclaimed > 0) {
        totalReclaimed += event.oldGenReclaimed;
        totalOldGenBefore += event.oldGenBefore;
      }
    }

    return totalOldGenBefore > 0 ? (totalReclaimed * 100.0) / totalOldGenBefore : 100.0;
  }

  private double calculateGrowthRate() {
    if (oldGenGrowthRates.size() < 2) return 0;

    double first = oldGenGrowthRates.get(0);
    double last = oldGenGrowthRates.get(oldGenGrowthRates.size() - 1);
    return first > 0 ? ((last - first) / first) * 100 : 0;
  }

  private MemoryUsage getOldGenUsage() {
    for (MemoryPoolMXBean pool : memoryPools) {
      if (isOldGenPool(pool.getName())) {
        return pool.getUsage();
      }
    }
    return null;
  }

  private boolean isOldGenPool(String poolName) {
    return poolName.contains("Tenured") ||
            poolName.contains("Old") ||
            poolName.equals("PS Old Gen") ||
            poolName.equals("G1 Old Gen") ||
            poolName.contains("Old Gen");
  }

  private void notifyListeners(ListenerNotification notification) {
    for (MemoryLeakListener listener : listeners) {
      try {
        notification.notify(listener);
      } catch (Exception e) {
        LOG.error("Error notifying listener", e);
      }
    }
  }

  @FunctionalInterface
  private interface ListenerNotification {
    void notify(MemoryLeakListener listener);
  }

  public interface MemoryLeakListener {
    default void onMonitoringStarted() {}
    default void onMonitoringStopped() {}
    default void onBaselineEstablished(long heapUsed, long oldGenUsed) {}
    default void onGcEvent(GcEvent event) {}
    default void onMemoryStatsUpdate(MemoryLeakStats stats, double heapGrowth,
                                     double oldGenGrowth, double recentGrowth) {}
    default void onHighRiskLeakDetected(double growthPercent, double gcEfficiency) {}
    default void onWarningLeakDetected(double growthPercent, double gcEfficiency) {}
    default void onRapidGrowthDetected(double growthRate) {}
    default void onLeakPatternDetected(double increasePercent) {}
    default void onForceGcStarted() {}
    default void onForceGcCompleted(long recoveredBytes) {}
  }

  public static class MemoryLeakStats {
    public long heapUsed;
    public long heapCommitted;
    public long heapMax;
    public long oldGenUsed;
    public long oldGenCommitted;
    public long oldGenMax;
    public double gcEfficiency;
    public double growthRate;
    public int gcCount;

    public String getHeapUsedFormatted() {
      return formatBytes(heapUsed);
    }

    public String getOldGenUsedFormatted() {
      return formatBytes(oldGenUsed);
    }

    private String formatBytes(long bytes) {
      if (bytes < 1024) return bytes + " B";
      if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
      if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
      return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
  }

  public static class GcEvent {
    public long timestamp;
    public String gcName;
    public long duration;
    public long oldGenBefore;
    public long oldGenAfter;
    public long oldGenReclaimed;
  }
}
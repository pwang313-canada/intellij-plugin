package org.cakk.memoryleak.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import org.jetbrains.annotations.NotNull;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service(Service.Level.PROJECT)
public final class MemoryMonitorService {

  private static final Logger LOG = Logger.getInstance(MemoryMonitorService.class);

  private final Project project;
  private final MemoryMXBean memoryBean;
  private final List<MemoryPoolMXBean> memoryPools;
  private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
  private final List<GcEvent> gcEvents = new CopyOnWriteArrayList<>();
  private final List<MemorySnapshot> memorySnapshots = new CopyOnWriteArrayList<>();
  private final List<MemoryMonitorListener> listeners = new CopyOnWriteArrayList<>();

  // Statistics for leak detection
  private long baselineUsedHeap = 0;
  private long baselineOldGen = 0;
  private final List<Double> oldGenGrowthRates = new ArrayList<>();

  // Detection thresholds
  private static final double RAPID_GROWTH_THRESHOLD = 10.0;
  private static final double WARNING_GROWTH_THRESHOLD = 20.0;
  private static final double HIGH_RISK_GROWTH_THRESHOLD = 50.0;
  private static final double LOW_GC_EFFICIENCY_THRESHOLD = 20.0;
  private static final double CRITICAL_GC_EFFICIENCY_THRESHOLD = 10.0;

  // Data classes
  public record GcEvent(
          long timestamp,
          String gcName,
          long duration,
          long oldGenBefore,
          long oldGenAfter,
          long oldGenReclaimed
  ) {}

  public record MemorySnapshot(
          long timestamp,
          long heapUsed,
          long heapCommitted,
          long heapMax,
          long oldGenUsed,
          long oldGenCommitted,
          long youngGenUsed,
          long metaspaceUsed
  ) {}

  public record LeakAlert(
          long timestamp,
          Severity severity,
          String message,
          Map<String, Object> details
  ) {
    public enum Severity { INFO, WARNING, HIGH_RISK, CRITICAL }
  }

  public record RapidGrowthAlert(
          long timestamp,
          double growthRate,
          double oldGenGrowthPercent,
          double gcEfficiency,
          long currentOldGen,
          long baselineOldGen
  ) {}

  public MemoryMonitorService(Project project) {
    this.project = project;
    this.memoryBean = ManagementFactory.getMemoryMXBean();
    this.memoryPools = ManagementFactory.getMemoryPoolMXBeans();
  }

  /**
   * Start memory monitoring
   */
  public void startMonitoring() {
    if (isMonitoring.compareAndSet(false, true)) {
      LOG.info("Starting memory monitoring");
      establishBaseline();
      setupGcListener();
      startPeriodicMonitoring();
      notifyMonitoringStateChanged(true);
    }
  }

  /**
   * Stop memory monitoring
   */
  public void stopMonitoring() {
    if (isMonitoring.compareAndSet(true, false)) {
      LOG.info("Stopping memory monitoring");
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      notifyMonitoringStateChanged(false);
    }
  }

  /**
   * Check if monitoring is active
   */
  public boolean isMonitoring() {
    return isMonitoring.get();
  }

  /**
   * Force garbage collection
   */
  public void forceGc() {
    LOG.info("Manual GC triggered");
    System.gc();
  }

  /**
   * Get current memory snapshot
   */
  public MemorySnapshot getCurrentSnapshot() {
    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    MemoryUsage oldGenUsage = getOldGenUsage();
    MemoryUsage youngGenUsage = getYoungGenUsage();
    MemoryUsage metaspaceUsage = getMetaspaceUsage();

    return new MemorySnapshot(
            System.currentTimeMillis(),
            heapUsage.getUsed(),
            heapUsage.getCommitted(),
            heapUsage.getMax(),
            oldGenUsage != null ? oldGenUsage.getUsed() : 0,
            oldGenUsage != null ? oldGenUsage.getCommitted() : 0,
            youngGenUsage != null ? youngGenUsage.getUsed() : 0,
            metaspaceUsage != null ? metaspaceUsage.getUsed() : 0
    );
  }

  /**
   * Add a memory monitor listener
   */
  public void addListener(MemoryMonitorListener listener) {
    listeners.add(listener);
  }

  /**
   * Remove a memory monitor listener
   */
  public void removeListener(MemoryMonitorListener listener) {
    listeners.remove(listener);
  }

  /**
   * Get recent GC events
   */
  public List<GcEvent> getRecentGcEvents(int maxCount) {
    int start = Math.max(0, gcEvents.size() - maxCount);
    return new ArrayList<>(gcEvents.subList(start, gcEvents.size()));
  }

  /**
   * Get all GC events
   */
  public List<GcEvent> getAllGcEvents() {
    return new ArrayList<>(gcEvents);
  }

  /**
   * Clear all GC events
   */
  public void clearEvents() {
    gcEvents.clear();
    memorySnapshots.clear();
    synchronized (oldGenGrowthRates) {
      oldGenGrowthRates.clear();
    }
  }

  /**
   * Format bytes for display
   */
  public String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  /**
   * Get formatted memory summary
   */
  public String getMemorySummary() {
    MemorySnapshot snapshot = getCurrentSnapshot();
    return String.format(
            "Heap: %s / %s (%.1f%%), Old Gen: %s, Young Gen: %s",
            formatBytes(snapshot.heapUsed()),
            formatBytes(snapshot.heapCommitted()),
            (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted(),
            formatBytes(snapshot.oldGenUsed()),
            formatBytes(snapshot.youngGenUsed())
    );
  }

  // Private methods

  private void establishBaseline() {
    LOG.info("Establishing baseline memory usage...");

    // Run GC to get clean state
    System.gc();
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    MemorySnapshot snapshot = getCurrentSnapshot();
    baselineUsedHeap = snapshot.heapUsed();
    baselineOldGen = snapshot.oldGenUsed();

    LOG.info(String.format("Baseline - Heap Used: %s, Old Gen Used: %s",
            formatBytes(baselineUsedHeap), formatBytes(baselineOldGen)));

    notifyBaselineEstablished(baselineUsedHeap, baselineOldGen);
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
        LOG.info("GC listener registered for: " + gcBean.getName());
      }
    }
  }

  private void analyzeGcEvent(GarbageCollectionNotificationInfo info) {
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

    gcEvents.add(event);

    // Keep only last 100 events
    while (gcEvents.size() > 100) {
      gcEvents.remove(0);
    }

    // Notify listeners
    notifyGcEvent(event);

    // Check for leak patterns
    detectLeakPatterns();
  }

  private void startPeriodicMonitoring() {
    scheduler.scheduleAtFixedRate(() -> {
      if (!isMonitoring.get()) return;

      try {
        MemorySnapshot snapshot = getCurrentSnapshot();
        memorySnapshots.add(snapshot);

        // Keep only last 1000 snapshots
        while (memorySnapshots.size() > 1000) {
          memorySnapshots.remove(0);
        }

        // Calculate metrics
        double heapGrowthPercent = baselineUsedHeap > 0 ?
                ((snapshot.heapUsed() - baselineUsedHeap) * 100.0) / baselineUsedHeap : 0;

        double oldGenGrowthPercent = baselineOldGen > 0 ?
                ((snapshot.oldGenUsed() - baselineOldGen) * 100.0) / baselineOldGen : 0;

        // Calculate recent growth rate
        double recentGrowthRate;
        synchronized (oldGenGrowthRates) {
          oldGenGrowthRates.add((double) snapshot.oldGenUsed());
          if (oldGenGrowthRates.size() > 10) {
            oldGenGrowthRates.remove(0);
          }

          if (oldGenGrowthRates.size() >= 2) {
            double first = oldGenGrowthRates.get(0);
            double last = oldGenGrowthRates.get(oldGenGrowthRates.size() - 1);
            recentGrowthRate = first > 0 ? ((last - first) / first) * 100 : 0;
          } else {
            recentGrowthRate = 0;
          }
        }

        double gcEfficiency = calculateGcEfficiency();

        // Check memory threshold warning
        double heapUsagePercent = (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted();
        double oldGenUsagePercent = snapshot.oldGenCommitted() > 0 ?
                (snapshot.oldGenUsed() * 100.0) / snapshot.oldGenCommitted() : 0;

        if (heapUsagePercent > 90 || oldGenUsagePercent > 90) {
          notifyMemoryThresholdWarning(heapUsagePercent, oldGenUsagePercent);
        }

        // Notify listeners of memory update
        notifyMemoryUpdate(snapshot);

        // Check for rapid growth
        if (recentGrowthRate > RAPID_GROWTH_THRESHOLD) {
          RapidGrowthAlert alert = new RapidGrowthAlert(
                  System.currentTimeMillis(),
                  recentGrowthRate,
                  oldGenGrowthPercent,
                  gcEfficiency,
                  snapshot.oldGenUsed(),
                  baselineOldGen
          );
          notifyRapidGrowth(alert);
        }

        // Check for leaks
        checkForLeaks(snapshot, heapGrowthPercent, oldGenGrowthPercent,
                recentGrowthRate, gcEfficiency);

        // Log status periodically
        if (LOG.isDebugEnabled()) {
          LOG.debug(String.format(
                  "Memory Status - Heap: %s (%.1f%%), Old Gen: %s (%.1f%%)",
                  formatBytes(snapshot.heapUsed()), heapGrowthPercent,
                  formatBytes(snapshot.oldGenUsed()), oldGenGrowthPercent
          ));
        }

      } catch (Exception e) {
        LOG.warn("Error during memory monitoring", e);
      }
    }, 5, 5, TimeUnit.SECONDS);
  }

  private void checkForLeaks(MemorySnapshot snapshot, double heapGrowthPercent,
                             double oldGenGrowthPercent, double recentGrowthRate,
                             double gcEfficiency) {

    // Check for high risk patterns
    if (oldGenGrowthPercent > HIGH_RISK_GROWTH_THRESHOLD &&
            gcEfficiency < CRITICAL_GC_EFFICIENCY_THRESHOLD) {

      LeakAlert alert = new LeakAlert(
              System.currentTimeMillis(),
              LeakAlert.Severity.HIGH_RISK,
              "High risk memory leak detected! Old gen growing >50% with poor GC efficiency",
              Map.of(
                      "oldGenGrowth", oldGenGrowthPercent,
                      "gcEfficiency", gcEfficiency,
                      "heapUsed", formatBytes(snapshot.heapUsed()),
                      "oldGenUsed", formatBytes(snapshot.oldGenUsed())
              )
      );
      notifyLeakDetected(alert);

    } else if (oldGenGrowthPercent > WARNING_GROWTH_THRESHOLD &&
            gcEfficiency < LOW_GC_EFFICIENCY_THRESHOLD) {

      LeakAlert alert = new LeakAlert(
              System.currentTimeMillis(),
              LeakAlert.Severity.WARNING,
              "Potential memory leak: old gen growing steadily",
              Map.of(
                      "oldGenGrowth", oldGenGrowthPercent,
                      "gcEfficiency", gcEfficiency,
                      "recentGrowthRate", recentGrowthRate
              )
      );
      notifyLeakDetected(alert);
    }
  }

  private void detectLeakPatterns() {
    if (gcEvents.size() < 10) return;

    List<GcEvent> last10 = gcEvents.subList(gcEvents.size() - 10, gcEvents.size());
    boolean increasing = true;
    Long previous = null;

    for (GcEvent event : last10) {
      if (event.oldGenAfter() > 0) {
        if (previous != null && event.oldGenAfter() <= previous) {
          increasing = false;
          break;
        }
        previous = event.oldGenAfter();
      }
    }

    if (increasing && previous != null) {
      long firstValue = last10.get(0).oldGenAfter();
      long lastValue = last10.get(last10.size() - 1).oldGenAfter();
      double increase = ((lastValue - firstValue) * 100.0) / firstValue;

      if (increase > 20) {
        LeakAlert alert = new LeakAlert(
                System.currentTimeMillis(),
                LeakAlert.Severity.CRITICAL,
                String.format("Memory leak pattern detected! Old gen increasing after each GC (↑%.1f%%)", increase),
                Map.of(
                        "increase", increase,
                        "gcCount", last10.size(),
                        "firstOldGen", formatBytes(firstValue),
                        "lastOldGen", formatBytes(lastValue)
                )
        );
        notifyLeakDetected(alert);
      }
    }
  }

  private double calculateGcEfficiency() {
    if (gcEvents.isEmpty()) return 100.0;

    long totalReclaimed = 0;
    long totalOldGenBefore = 0;

    for (GcEvent event : gcEvents) {
      if (event.oldGenReclaimed() > 0) {
        totalReclaimed += event.oldGenReclaimed();
        totalOldGenBefore += event.oldGenBefore();
      }
    }

    return totalOldGenBefore > 0 ?
            (totalReclaimed * 100.0) / totalOldGenBefore : 100.0;
  }

  private MemoryUsage getOldGenUsage() {
    for (MemoryPoolMXBean pool : memoryPools) {
      if (isOldGenPool(pool.getName())) {
        return pool.getUsage();
      }
    }
    return null;
  }

  private MemoryUsage getYoungGenUsage() {
    for (MemoryPoolMXBean pool : memoryPools) {
      String name = pool.getName();
      if (name.contains("Eden") || name.contains("Young") ||
              name.equals("PS Eden Space") || name.equals("G1 Eden Space")) {
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
    return poolName.contains("Tenured") ||
            poolName.contains("Old") ||
            poolName.equals("PS Old Gen") ||
            poolName.equals("G1 Old Gen") ||
            poolName.contains("Old Gen");
  }

  // Notification methods

  private void notifyMemoryUpdate(MemorySnapshot snapshot) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onMemoryUpdate(snapshot);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of memory update", e);
      }
    }
  }

  private void notifyGcEvent(GcEvent event) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onGcEvent(event);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of GC event", e);
      }
    }
  }

  private void notifyLeakDetected(LeakAlert alert) {
    LOG.warn("Leak detected: " + alert.message());
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onLeakDetected(alert);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of leak detection", e);
      }
    }
  }

  private void notifyRapidGrowth(RapidGrowthAlert alert) {
    LOG.warn("Rapid growth detected: " + alert.growthRate() + "%");
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onRapidGrowthDetected(alert);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of rapid growth", e);
      }
    }
  }

  private void notifyMonitoringStateChanged(boolean isMonitoring) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onMonitoringStateChanged(isMonitoring);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of state change", e);
      }
    }
  }

  private void notifyBaselineEstablished(long baselineHeap, long baselineOldGen) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onBaselineEstablished(baselineHeap, baselineOldGen);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of baseline", e);
      }
    }
  }

  private void notifyMemoryThresholdWarning(double heapUsagePercent, double oldGenUsagePercent) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onMemoryThresholdWarning(heapUsagePercent, oldGenUsagePercent);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of threshold warning", e);
      }
    }
  }

  // Add this interface inside MemoryMonitorService class
  public interface MemoryMonitorListener {

    /**
     * Called when a new memory snapshot is taken
     */
    void onMemoryUpdate(@NotNull MemorySnapshot snapshot);

    /**
     * Called when a garbage collection event occurs
     */
    void onGcEvent(@NotNull GcEvent event);

    /**
     * Called when a memory leak is detected
     */
    void onLeakDetected(@NotNull LeakAlert alert);

    /**
     * Called when rapid old generation growth is detected
     */
    void onRapidGrowthDetected(@NotNull RapidGrowthAlert alert);

    /**
     * Optional: Called when monitoring starts or stops
     */
    default void onMonitoringStateChanged(boolean isMonitoring) {}

    /**
     * Optional: Called when baseline is established
     */
    default void onBaselineEstablished(long baselineHeap, long baselineOldGen) {}

    /**
     * Optional: Called when memory usage crosses a threshold
     */
    default void onMemoryThresholdWarning(double heapUsagePercent, double oldGenUsagePercent) {}
  }
}
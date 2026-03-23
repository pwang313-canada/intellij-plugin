// src/main/java/org/cakk/memoryleak/services/MemoryMonitorService.java
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

  // Leak pattern tracking
  private long previousOldGenAfterGc = 0;
  private int baselineIncreaseCount = 0;
  private final List<Long> oldGenAfterGcHistory = new ArrayList<>();
  private final List<Double> gcEfficiencyHistory = new ArrayList<>();

  // Current metrics (for quick access)
  private volatile double currentHeapGrowthPercent = 0;
  private volatile double currentOldGenGrowthPercent = 0;
  private volatile double currentRecentGrowthRate = 0;
  private volatile double currentGcEfficiency = 100;

  // Detection thresholds
  private static final double RAPID_GROWTH_THRESHOLD = 10.0;
  private static final double WARNING_GROWTH_THRESHOLD = 20.0;
  private static final double HIGH_RISK_GROWTH_THRESHOLD = 50.0;
  private static final double LOW_GC_EFFICIENCY_THRESHOLD = 20.0;
  private static final double CRITICAL_GC_EFFICIENCY_THRESHOLD = 10.0;
  private static final int BASELINE_INCREASE_ALERT_THRESHOLD = 3;

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

  public record LeakAnalysisReport(
          long timestamp,
          boolean hasLeak,
          double baselineTrendMB,
          double averageGcEfficiency,
          String recommendation,
          Map<String, Object> details
  ) {}

  // Listener interface
  public interface MemoryMonitorListener {
    void onMemoryUpdate(@NotNull MemorySnapshot snapshot);
    void onGcEvent(@NotNull GcEvent event);
    void onLeakDetected(@NotNull LeakAlert alert);
    void onRapidGrowthDetected(@NotNull RapidGrowthAlert alert);

    default void onMonitoringStateChanged(boolean isMonitoring) {}
    default void onBaselineEstablished(long baselineHeap, long baselineOldGen) {}
    default void onMemoryThresholdWarning(double heapUsagePercent, double oldGenUsagePercent) {}
    default void onLeakAnalysisReport(@NotNull LeakAnalysisReport report) {}
    default void onMetricsUpdate(double heapGrowthPercent, double oldGenGrowthPercent,
                                 double recentGrowthRate, double gcEfficiency) {}
    default void onMonitoringError(@NotNull Throwable error, @NotNull String context) {}
  }

  public MemoryMonitorService(Project project) {
    this.project = project;
    this.memoryBean = ManagementFactory.getMemoryMXBean();
    this.memoryPools = ManagementFactory.getMemoryPoolMXBeans();
  }

  // ========== PUBLIC API METHODS ==========

  public void startMonitoring() {
    if (isMonitoring.compareAndSet(false, true)) {
      LOG.info("Starting memory monitoring");
      try {
        establishBaseline();
        setupGcListener();
        startPeriodicMonitoring();
        notifyMonitoringStateChanged(true);
      } catch (Exception e) {
        LOG.error("Failed to start monitoring", e);
        notifyMonitoringError(e, "startMonitoring");
        isMonitoring.set(false);
      }
    }
  }

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

  public boolean isMonitoring() {
    return isMonitoring.get();
  }

  public void forceGc() {
    LOG.info("Manual GC triggered");
    System.gc();
    appendLog("[Manual GC] System.gc() called");
  }

  public MemorySnapshot getCurrentSnapshot() {
    try {
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
    } catch (Exception e) {
      LOG.warn("Error getting memory snapshot", e);
      return new MemorySnapshot(
              System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0
      );
    }
  }

  public void addListener(MemoryMonitorListener listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  public void removeListener(MemoryMonitorListener listener) {
    listeners.remove(listener);
  }

  public List<GcEvent> getRecentGcEvents(int maxCount) {
    int start = Math.max(0, gcEvents.size() - maxCount);
    return new ArrayList<>(gcEvents.subList(start, gcEvents.size()));
  }

  public List<GcEvent> getAllGcEvents() {
    return new ArrayList<>(gcEvents);
  }

  public void clearEvents() {
    gcEvents.clear();
    memorySnapshots.clear();
    synchronized (oldGenGrowthRates) {
      oldGenGrowthRates.clear();
    }
    oldGenAfterGcHistory.clear();
    gcEfficiencyHistory.clear();
    baselineIncreaseCount = 0;
  }

  public double getCurrentHeapGrowthPercent() {
    return currentHeapGrowthPercent;
  }

  public double getCurrentOldGenGrowthPercent() {
    return currentOldGenGrowthPercent;
  }

  public double getCurrentRecentGrowthRate() {
    return currentRecentGrowthRate;
  }

  public double getCurrentGcEfficiency() {
    return currentGcEfficiency;
  }

  // ========== LEAK ANALYSIS METHODS ==========

  public LeakAnalysisReport analyzeLeakPattern() {
    if (gcEvents.size() < 5) {
      return new LeakAnalysisReport(
              System.currentTimeMillis(),
              false,
              0,
              0,
              "Not enough GC data for analysis. Need at least 5 GC events.",
              Map.of("gcEventCount", gcEvents.size())
      );
    }

    // Analyze recent GC events
    List<GcEvent> recentEvents = gcEvents.subList(gcEvents.size() - Math.min(10, gcEvents.size()), gcEvents.size());

    // Calculate old gen after GC trend
    long firstOldGenAfter = recentEvents.get(0).oldGenAfter();
    long lastOldGenAfter = recentEvents.get(recentEvents.size() - 1).oldGenAfter();
    long baselineTrend = lastOldGenAfter - firstOldGenAfter;
    double baselineTrendMB = baselineTrend / (1024.0 * 1024);

    // Calculate average GC efficiency
    double avgEfficiency = recentEvents.stream()
            .filter(e -> e.oldGenBefore() > 0)
            .mapToDouble(e -> (double) e.oldGenReclaimed() / e.oldGenBefore() * 100)
            .average()
            .orElse(0);

    // Determine if there's a leak
    boolean hasLeak = false;
    String recommendation;
    Map<String, Object> details = new HashMap<>();

    details.put("baselineTrendMB", baselineTrendMB);
    details.put("averageGcEfficiency", avgEfficiency);
    details.put("gcEventCount", recentEvents.size());
    details.put("firstOldGenAfterMB", firstOldGenAfter / (1024.0 * 1024));
    details.put("lastOldGenAfterMB", lastOldGenAfter / (1024.0 * 1024));

    if (baselineTrend > 50 * 1024 * 1024 && avgEfficiency < CRITICAL_GC_EFFICIENCY_THRESHOLD) {
      hasLeak = true;
      recommendation = "CRITICAL: Old gen baseline increased by over 50MB with very poor GC efficiency (<10%). " +
              "This strongly indicates a severe memory leak. Check for:\n" +
              "• Static collections that keep growing (List, Map, Set)\n" +
              "• ThreadLocal variables without remove() calls\n" +
              "• Unclosed resources (connections, streams, files)\n" +
              "• Caches without eviction policies\n" +
              "• Event listeners that are never unregistered";
      details.put("severity", "CRITICAL");
    } else if (baselineTrend > 10 * 1024 * 1024 && avgEfficiency < LOW_GC_EFFICIENCY_THRESHOLD) {
      hasLeak = true;
      recommendation = "HIGH RISK: Old gen baseline is increasing with poor GC efficiency. " +
              "Memory leak likely. Review code for:\n" +
              "• Objects held in collections longer than needed\n" +
              "• Inner class instances holding outer class references\n" +
              "• Large temporary object allocations\n" +
              "• Inefficient caching strategies";
      details.put("severity", "HIGH_RISK");
    } else if (baselineTrend > 5 * 1024 * 1024) {
      hasLeak = true;
      recommendation = "WARNING: Old gen baseline is trending upward. Monitor closely. " +
              "Consider reviewing:\n" +
              "• Object lifecycle management\n" +
              "• Collection cleanup routines\n" +
              "• Resource cleanup in finally blocks";
      details.put("severity", "WARNING");
    } else if (avgEfficiency < LOW_GC_EFFICIENCY_THRESHOLD) {
      recommendation = "Poor GC efficiency detected. Objects are moving to old generation too quickly. " +
              "Check for:\n" +
              "• Large object allocations that bypass young gen\n" +
              "• Survivor space sizing issues\n" +
              "• Premature promotion of objects";
      details.put("severity", "INFO");
    } else {
      recommendation = "No memory leak detected. Memory usage patterns are healthy.\n" +
              "• Old gen baseline is stable\n" +
              "• GC efficiency is good\n" +
              "• No abnormal growth patterns";
      details.put("severity", "HEALTHY");
    }

    // Add more details
    details.put("gcEfficiencyHistory", gcEfficiencyHistory);
    details.put("oldGenAfterGcHistory", oldGenAfterGcHistory);

    return new LeakAnalysisReport(
            System.currentTimeMillis(),
            hasLeak,
            baselineTrendMB,
            avgEfficiency,
            recommendation,
            details
    );
  }

  public String getFormattedLeakAnalysis() {
    LeakAnalysisReport report = analyzeLeakPattern();
    StringBuilder sb = new StringBuilder();
    sb.append("\n╔══════════════════════════════════════════════════════════════╗\n");
    sb.append("║              MEMORY LEAK ANALYSIS REPORT                      ║\n");
    sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

    sb.append("📊 ANALYSIS SUMMARY:\n");
    sb.append("   ┌─────────────────────────────────────────────────────────┐\n");
    sb.append(String.format("   │ Leak Detected:     %s%n", report.hasLeak() ? "⚠️ YES" : "✅ NO"));
    sb.append(String.format("   │ Baseline Trend:    %+.2f MB%n", report.baselineTrendMB()));
    sb.append(String.format("   │ GC Efficiency:     %.1f%%%n", report.averageGcEfficiency()));
    sb.append(String.format("   │ GC Events Analyzed: %d%n", report.details().get("gcEventCount")));
    sb.append("   └─────────────────────────────────────────────────────────┘\n\n");

    sb.append("🔍 RECOMMENDATION:\n");
    sb.append(report.recommendation());
    sb.append("\n");

    return sb.toString();
  }

  public String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  public String getMemorySummary() {
    MemorySnapshot snapshot = getCurrentSnapshot();
    return String.format(
            "Heap: %s / %s (%.1f%%), Old Gen: %s, Young Gen: %s",
            formatBytes(snapshot.heapUsed()),
            formatBytes(snapshot.heapCommitted()),
            snapshot.heapCommitted() > 0 ? (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted() : 0,
            formatBytes(snapshot.oldGenUsed()),
            formatBytes(snapshot.youngGenUsed())
    );
  }

  // ========== PRIVATE METHODS ==========

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
    try {
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

      double efficiency = oldGenBefore > 0 ?
              ((oldGenBefore - oldGenAfter) * 100.0) / oldGenBefore : 0;

      GcEvent event = new GcEvent(
              System.currentTimeMillis(),
              info.getGcName(),
              gcInfo.getDuration(),
              oldGenBefore,
              oldGenAfter,
              oldGenBefore - oldGenAfter
      );

      gcEvents.add(event);

      // Keep only last 200 events
      while (gcEvents.size() > 200) {
        gcEvents.remove(0);
      }

      // Track baseline trend for leak detection
      if (previousOldGenAfterGc > 0) {
        long baselineChange = event.oldGenAfter() - previousOldGenAfterGc;
        if (baselineChange > 0) {
          baselineIncreaseCount++;
          oldGenAfterGcHistory.add(event.oldGenAfter());
          gcEfficiencyHistory.add(efficiency);

          while (oldGenAfterGcHistory.size() > 20) {
            oldGenAfterGcHistory.remove(0);
          }
          while (gcEfficiencyHistory.size() > 20) {
            gcEfficiencyHistory.remove(0);
          }

          if (baselineIncreaseCount >= BASELINE_INCREASE_ALERT_THRESHOLD) {
            LeakAlert alert = new LeakAlert(
                    System.currentTimeMillis(),
                    LeakAlert.Severity.WARNING,
                    String.format("Memory leak pattern: Old gen baseline rising! After %d GC cycles",
                            baselineIncreaseCount),
                    Map.of(
                            "baselineIncreaseCount", baselineIncreaseCount,
                            "baselineChange", formatBytes(baselineChange),
                            "currentOldGen", formatBytes(event.oldGenAfter()),
                            "gcEfficiency", efficiency
                    )
            );
            notifyLeakDetected(alert);
          }
        } else {
          baselineIncreaseCount = Math.max(0, baselineIncreaseCount - 1);
        }
      }

      previousOldGenAfterGc = event.oldGenAfter();
      currentGcEfficiency = efficiency;

      notifyGcEvent(event);
      detectLeakPatterns();

    } catch (Exception e) {
      LOG.warn("Error analyzing GC event", e);
    }
  }

  private void startPeriodicMonitoring() {
    scheduler.scheduleAtFixedRate(() -> {
      if (!isMonitoring.get()) return;

      try {
        MemorySnapshot snapshot = getCurrentSnapshot();
        memorySnapshots.add(snapshot);

        while (memorySnapshots.size() > 1000) {
          memorySnapshots.remove(0);
        }

        // Calculate growth percentages
        currentHeapGrowthPercent = baselineUsedHeap > 0 ?
                ((snapshot.heapUsed() - baselineUsedHeap) * 100.0) / baselineUsedHeap : 0;

        currentOldGenGrowthPercent = baselineOldGen > 0 ?
                ((snapshot.oldGenUsed() - baselineOldGen) * 100.0) / baselineOldGen : 0;

        // Calculate recent growth rate
        synchronized (oldGenGrowthRates) {
          oldGenGrowthRates.add((double) snapshot.oldGenUsed());
          if (oldGenGrowthRates.size() > 10) {
            oldGenGrowthRates.remove(0);
          }

          if (oldGenGrowthRates.size() >= 2) {
            double first = oldGenGrowthRates.get(0);
            double last = oldGenGrowthRates.get(oldGenGrowthRates.size() - 1);
            currentRecentGrowthRate = first > 0 ? ((last - first) / first) * 100 : 0;
          } else {
            currentRecentGrowthRate = 0;
          }
        }

        // Update GC efficiency
        currentGcEfficiency = calculateGcEfficiency();

        // Check memory threshold warning
        double heapUsagePercent = snapshot.heapCommitted() > 0 ?
                (snapshot.heapUsed() * 100.0) / snapshot.heapCommitted() : 0;
        double oldGenUsagePercent = snapshot.oldGenCommitted() > 0 ?
                (snapshot.oldGenUsed() * 100.0) / snapshot.oldGenCommitted() : 0;

        if (heapUsagePercent > 90 || oldGenUsagePercent > 90) {
          notifyMemoryThresholdWarning(heapUsagePercent, oldGenUsagePercent);
        }

        // Notify listeners
        notifyMemoryUpdate(snapshot);
        notifyMetricsUpdate(currentHeapGrowthPercent, currentOldGenGrowthPercent,
                currentRecentGrowthRate, currentGcEfficiency);

        // Check for rapid growth
        if (currentRecentGrowthRate > RAPID_GROWTH_THRESHOLD) {
          RapidGrowthAlert alert = new RapidGrowthAlert(
                  System.currentTimeMillis(),
                  currentRecentGrowthRate,
                  currentOldGenGrowthPercent,
                  currentGcEfficiency,
                  snapshot.oldGenUsed(),
                  baselineOldGen
          );
          notifyRapidGrowth(alert);
        }

        // Check for leaks
        checkForLeaks(snapshot, currentHeapGrowthPercent, currentOldGenGrowthPercent,
                currentRecentGrowthRate, currentGcEfficiency);

        if (LOG.isDebugEnabled()) {
          LOG.debug(String.format(
                  "Memory Status - Heap: %s (%.1f%%), Old Gen: %s (%.1f%%), GC Eff: %.1f%%",
                  formatBytes(snapshot.heapUsed()), currentHeapGrowthPercent,
                  formatBytes(snapshot.oldGenUsed()), currentOldGenGrowthPercent,
                  currentGcEfficiency
          ));
        }

      } catch (Exception e) {
        LOG.warn("Error during memory monitoring", e);
        notifyMonitoringError(e, "periodicMonitoring");
      }
    }, 5, 5, TimeUnit.SECONDS);

    // Schedule periodic leak analysis
    scheduler.scheduleAtFixedRate(() -> {
      if (isMonitoring.get() && gcEvents.size() >= 5) {
        try {
          LeakAnalysisReport report = analyzeLeakPattern();
          notifyLeakAnalysisReport(report);
        } catch (Exception e) {
          LOG.warn("Error during leak analysis", e);
        }
      }
    }, 30, 30, TimeUnit.SECONDS);
  }

  private void checkForLeaks(MemorySnapshot snapshot, double heapGrowthPercent,
                             double oldGenGrowthPercent, double recentGrowthRate,
                             double gcEfficiency) {

    if (oldGenGrowthPercent > HIGH_RISK_GROWTH_THRESHOLD &&
            gcEfficiency < CRITICAL_GC_EFFICIENCY_THRESHOLD) {

      LeakAlert alert = new LeakAlert(
              System.currentTimeMillis(),
              LeakAlert.Severity.HIGH_RISK,
              "HIGH RISK: Old gen growing >50% with very poor GC efficiency! Memory leak detected!",
              Map.of(
                      "oldGenGrowth", oldGenGrowthPercent,
                      "gcEfficiency", gcEfficiency,
                      "heapUsed", formatBytes(snapshot.heapUsed()),
                      "oldGenUsed", formatBytes(snapshot.oldGenUsed()),
                      "heapGrowth", heapGrowthPercent,
                      "recentGrowthRate", recentGrowthRate
              )
      );
      notifyLeakDetected(alert);

    } else if (oldGenGrowthPercent > WARNING_GROWTH_THRESHOLD &&
            gcEfficiency < LOW_GC_EFFICIENCY_THRESHOLD) {

      LeakAlert alert = new LeakAlert(
              System.currentTimeMillis(),
              LeakAlert.Severity.WARNING,
              "WARNING: Old gen growing steadily with poor GC efficiency. Potential memory leak.",
              Map.of(
                      "oldGenGrowth", oldGenGrowthPercent,
                      "gcEfficiency", gcEfficiency,
                      "recentGrowthRate", recentGrowthRate
              )
      );
      notifyLeakDetected(alert);

    } else if (oldGenGrowthPercent > WARNING_GROWTH_THRESHOLD) {
      LeakAlert alert = new LeakAlert(
              System.currentTimeMillis(),
              LeakAlert.Severity.INFO,
              "INFO: Old gen growth detected. Monitor closely for memory leak patterns.",
              Map.of(
                      "oldGenGrowth", oldGenGrowthPercent,
                      "currentOldGen", formatBytes(snapshot.oldGenUsed())
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
                String.format("CRITICAL: Old gen increasing after each GC (↑%.1f%% over last 10 GCs)", increase),
                Map.of(
                        "increase", increase,
                        "gcCount", last10.size(),
                        "firstOldGen", formatBytes(firstValue),
                        "lastOldGen", formatBytes(lastValue),
                        "trend", "increasing"
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

  private void appendLog(String message) {
    // For debugging
    if (LOG.isDebugEnabled()) {
      LOG.debug(message);
    }
  }

  // ========== NOTIFICATION METHODS ==========

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

  private void notifyLeakAnalysisReport(LeakAnalysisReport report) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onLeakAnalysisReport(report);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of leak analysis", e);
      }
    }
  }

  private void notifyMetricsUpdate(double heapGrowthPercent, double oldGenGrowthPercent,
                                   double recentGrowthRate, double gcEfficiency) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onMetricsUpdate(heapGrowthPercent, oldGenGrowthPercent,
                recentGrowthRate, gcEfficiency);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of metrics update", e);
      }
    }
  }

  private void notifyMonitoringError(Throwable error, String context) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onMonitoringError(error, context);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of monitoring error", e);
      }
    }
  }
}
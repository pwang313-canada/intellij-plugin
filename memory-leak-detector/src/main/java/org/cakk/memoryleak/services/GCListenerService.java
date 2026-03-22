// src/main/java/org/cakk/services/GCListenerService.java
// Update the notifyGCEvent method

package org.cakk.memoryleak.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.PROJECT)
public final class GCListenerService {

  private static final Logger LOG = Logger.getInstance(GCListenerService.class);

  private final Project project;
  private final List<GCListener> listeners = new CopyOnWriteArrayList<>();
  private final Map<String, GCStatistics> gcStatistics = new ConcurrentHashMap<>();

  // GC Event tracking
  private final List<GCEvent> gcEvents = new CopyOnWriteArrayList<>();
  private final AtomicLong totalGcCount = new AtomicLong(0);
  private final AtomicLong totalGcDuration = new AtomicLong(0);
  private final AtomicLong totalOldGenReclaimed = new AtomicLong(0);

  // GC thresholds for alerts
  private static final long LONG_GC_THRESHOLD_MS = 1000; // 1 second
  private static final double LOW_RECLAIM_THRESHOLD = 10.0; // 10% reclaim efficiency

  public GCListenerService(Project project) {
    this.project = project;
    setupGCListeners();
  }

  /**
   * GC Event record
   */
  public record GCEvent(
          long timestamp,
          String gcName,
          String gcCause,
          long duration,
          long startTime,
          long endTime,
          long oldGenBefore,
          long oldGenAfter,
          long oldGenReclaimed,
          long youngGenBefore,
          long youngGenAfter,
          long youngGenReclaimed,
          long heapBefore,
          long heapAfter,
          long heapReclaimed,
          boolean isFullGC,
          double reclaimEfficiency
  ) {}

  /**
   * GC Statistics for a specific collector
   */
  public record GCStatistics(
          String gcName,
          long count,
          long totalDuration,
          long maxDuration,
          long minDuration,
          double avgDuration,
          long totalOldGenReclaimed,
          long totalYoungGenReclaimed,
          long lastGcTimestamp
  ) {}

  /**
   * GC Alert record
   */
  public record GCAlert(
          long timestamp,
          AlertType type,
          String message,
          GCEvent event,
          Map<String, Object> details
  ) {
    public enum AlertType {
      LONG_GC_PAUSE,
      FREQUENT_GC,
      INEFFICIENT_GC,
      FULL_GC,
      OLD_GEN_NOT_RECLAIMED,
      YOUNG_GEN_NOT_RECLAIMED
    }
  }

  /**
   * GC Listener interface
   */
  public interface GCListener {
    void onGCEvent(GCEvent event);
    void onGCAlert(GCAlert alert);
    void onGCStatisticsUpdated(GCStatistics statistics);
  }

  /**
   * Setup GC notification listeners for all garbage collectors
   */
  private void setupGCListeners() {
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    for (GarbageCollectorMXBean gcBean : gcBeans) {
      if (gcBean instanceof NotificationEmitter) {
        NotificationEmitter emitter = (NotificationEmitter) gcBean;
        GCNotificationListener listener = new GCNotificationListener(gcBean.getName());
        emitter.addNotificationListener(listener, null, gcBean.getName());
        LOG.info("GC listener registered for: " + gcBean.getName());
      } else {
        LOG.warn("Cannot register GC listener for: " + gcBean.getName() +
                " - not a NotificationEmitter");
      }
    }
  }

  /**
   * Internal notification listener for GC events
   */
  private class GCNotificationListener implements NotificationListener {
    private final String gcName;

    GCNotificationListener(String gcName) {
      this.gcName = gcName;
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
      if (notification.getType().equals(
              GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {

        CompositeData cd = (CompositeData) notification.getUserData();
        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from(cd);

        processGCEvent(gcName, info);
      }
    }
  }

  /**
   * Process a single GC event
   */
  private void processGCEvent(String gcName, GarbageCollectionNotificationInfo info) {
    try {
      GcInfo gcInfo = info.getGcInfo();

      Map<String, MemoryUsage> beforeMap = gcInfo.getMemoryUsageBeforeGc();
      Map<String, MemoryUsage> afterMap = gcInfo.getMemoryUsageAfterGc();

      // Extract memory usage before and after GC
      long oldGenBefore = 0;
      long oldGenAfter = 0;
      long youngGenBefore = 0;
      long youngGenAfter = 0;
      long heapBefore = 0;
      long heapAfter = 0;

      for (Map.Entry<String, MemoryUsage> entry : beforeMap.entrySet()) {
        String poolName = entry.getKey();
        MemoryUsage before = entry.getValue();
        MemoryUsage after = afterMap.get(poolName);

        if (isOldGenPool(poolName) && after != null) {
          oldGenBefore = before.getUsed();
          oldGenAfter = after.getUsed();
        } else if (isYoungGenPool(poolName) && after != null) {
          youngGenBefore = before.getUsed();
          youngGenAfter = after.getUsed();
        }

        if (poolName.contains("Heap")) {
          heapBefore = before.getUsed();
          heapAfter = after.getUsed();
        }
      }

      long oldGenReclaimed = oldGenBefore - oldGenAfter;
      long youngGenReclaimed = youngGenBefore - youngGenAfter;
      long heapReclaimed = heapBefore - heapAfter;

      double reclaimEfficiency = oldGenBefore > 0 ?
              (oldGenReclaimed * 100.0) / oldGenBefore : 0;

      boolean isFullGC = info.getGcName().toLowerCase().contains("full") ||
              info.getGcName().toLowerCase().contains("old") ||
              (oldGenReclaimed > 0 && info.getGcName().contains("CMS"));

      GCEvent event = new GCEvent(
              System.currentTimeMillis(),
              gcName,
              info.getGcCause(),
              gcInfo.getDuration(),
              gcInfo.getStartTime(),
              gcInfo.getEndTime(),
              oldGenBefore,
              oldGenAfter,
              oldGenReclaimed,
              youngGenBefore,
              youngGenAfter,
              youngGenReclaimed,
              heapBefore,
              heapAfter,
              heapReclaimed,
              isFullGC,
              reclaimEfficiency
      );

      // Use synchronized for thread safety
      synchronized (gcEvents) {
        gcEvents.add(event);
        while (gcEvents.size() > 1000) {
          gcEvents.remove(0);
        }
      }

      // Update statistics
      updateStatistics(gcName, event);

      // Check for GC issues and generate alerts
      checkForGCAlerts(event);

      // Notify listeners
      notifyGCEvent(event);

      // Log significant GC events (but don't log too much)
      if (isFullGC && event.duration() > 100) {
        LOG.info(String.format("GC Event: %s, Duration: %dms, Old Gen Reclaimed: %s",
                event.gcName(),
                event.duration(),
                formatBytes(event.oldGenReclaimed())
        ));
      }

    } catch (Exception e) {
      LOG.warn("Error processing GC event", e);
    }
  }
  /**
   * Update statistics for a GC collector
   */
  private void updateStatistics(String gcName, GCEvent event) {
    GCStatistics stats = gcStatistics.compute(gcName, (key, existing) -> {
      if (existing == null) {
        return new GCStatistics(
                gcName,
                1,
                event.duration(),
                event.duration(),
                event.duration(),
                event.duration(),
                event.oldGenReclaimed(),
                event.youngGenReclaimed(),
                event.timestamp()
        );
      } else {
        long newCount = existing.count() + 1;
        long newTotalDuration = existing.totalDuration() + event.duration();
        long newMaxDuration = Math.max(existing.maxDuration(), event.duration());
        long newMinDuration = Math.min(existing.minDuration(), event.duration());
        double newAvgDuration = newTotalDuration / (double) newCount;
        long newTotalOldGenReclaimed = existing.totalOldGenReclaimed() + event.oldGenReclaimed();
        long newTotalYoungGenReclaimed = existing.totalYoungGenReclaimed() + event.youngGenReclaimed();

        return new GCStatistics(
                gcName,
                newCount,
                newTotalDuration,
                newMaxDuration,
                newMinDuration,
                newAvgDuration,
                newTotalOldGenReclaimed,
                newTotalYoungGenReclaimed,
                event.timestamp()
        );
      }
    });

    // Notify statistics update
    notifyStatisticsUpdated(stats);
  }

  /**
   * Check for GC issues that might indicate memory leaks
   */
  private void checkForGCAlerts(GCEvent event) {
    List<GCAlert> alerts = new ArrayList<>();

    // Alert 1: Long GC pause
    if (event.duration() > LONG_GC_THRESHOLD_MS) {
      alerts.add(new GCAlert(
              System.currentTimeMillis(),
              GCAlert.AlertType.LONG_GC_PAUSE,
              String.format("Long GC pause detected: %d ms", event.duration()),
              event,
              Map.of(
                      "threshold", LONG_GC_THRESHOLD_MS,
                      "gcName", event.gcName(),
                      "duration", event.duration()
              )
      ));
    }

    // Alert 2: Full GC occurrence
    if (event.isFullGC()) {
      alerts.add(new GCAlert(
              System.currentTimeMillis(),
              GCAlert.AlertType.FULL_GC,
              "Full GC occurred - check for memory pressure",
              event,
              Map.of(
                      "gcName", event.gcName(),
                      "duration", event.duration(),
                      "oldGenReclaimed", event.oldGenReclaimed()
              )
      ));
    }

    // Alert 3: Inefficient GC (little old gen reclaimed)
    if (event.oldGenBefore() > 0 &&
            event.reclaimEfficiency() < LOW_RECLAIM_THRESHOLD &&
            event.duration() > 100) {
      alerts.add(new GCAlert(
              System.currentTimeMillis(),
              GCAlert.AlertType.INEFFICIENT_GC,
              String.format("Inefficient GC: only %.1f%% of old gen reclaimed",
                      event.reclaimEfficiency()),
              event,
              Map.of(
                      "reclaimEfficiency", event.reclaimEfficiency(),
                      "oldGenBefore", event.oldGenBefore(),
                      "oldGenAfter", event.oldGenAfter(),
                      "threshold", LOW_RECLAIM_THRESHOLD
              )
      ));
    }

    // Alert 4: Old gen not reclaimed at all in Full GC (potential leak)
    if (event.isFullGC() && event.oldGenReclaimed() == 0 && event.oldGenBefore() > 0) {
      alerts.add(new GCAlert(
              System.currentTimeMillis(),
              GCAlert.AlertType.OLD_GEN_NOT_RECLAIMED,
              "Full GC reclaimed no old gen memory - possible memory leak!",
              event,
              Map.of(
                      "oldGenBefore", formatBytes(event.oldGenBefore()),
                      "gcName", event.gcName()
              )
      ));
    }

    // Notify alerts
    for (GCAlert alert : alerts) {
      notifyGCAlert(alert);

      // Log severe alerts
      if (alert.type() == GCAlert.AlertType.OLD_GEN_NOT_RECLAIMED) {
        LOG.warn(alert.message());
      }
    }
  }

  /**
   * Get recent GC events
   */
  public List<GCEvent> getRecentGCEvents(int maxCount) {
    int start = Math.max(0, gcEvents.size() - maxCount);
    return new ArrayList<>(gcEvents.subList(start, gcEvents.size()));
  }

  /**
   * Get all GC events
   */
  public List<GCEvent> getAllGCEvents() {
    return new ArrayList<>(gcEvents);
  }

  /**
   * Get statistics for a specific GC collector
   */
  public GCStatistics getGCStatistics(String gcName) {
    return gcStatistics.get(gcName);
  }

  /**
   * Get all GC statistics
   */
  public Map<String, GCStatistics> getAllGCStatistics() {
    return new HashMap<>(gcStatistics);
  }

  /**
   * Get overall GC metrics
   */
  public GCMetrics getOverallGCMetrics() {
    long totalCount = 0;
    long totalDuration = 0;
    long totalOldGenReclaimed = 0;
    long totalYoungGenReclaimed = 0;
    long maxDuration = 0;
    int fullGCCount = 0;

    for (GCEvent event : gcEvents) {
      totalCount++;
      totalDuration += event.duration();
      totalOldGenReclaimed += event.oldGenReclaimed();
      totalYoungGenReclaimed += event.youngGenReclaimed();
      maxDuration = Math.max(maxDuration, event.duration());
      if (event.isFullGC()) {
        fullGCCount++;
      }
    }

    double avgDuration = totalCount > 0 ? totalDuration / (double) totalCount : 0;
    double avgEfficiency = totalCount > 0 && totalOldGenReclaimed > 0 ?
            (totalOldGenReclaimed / (double) totalOldGenReclaimed) * 100 : 0;

    return new GCMetrics(
            totalCount,
            fullGCCount,
            totalDuration,
            avgDuration,
            maxDuration,
            totalOldGenReclaimed,
            totalYoungGenReclaimed,
            avgEfficiency
    );
  }

  /**
   * GC Metrics record
   */
  public record GCMetrics(
          long totalGCCount,
          long fullGCCount,
          long totalGCDuration,
          double avgGCDuration,
          long maxGCDuration,
          long totalOldGenReclaimed,
          long totalYoungGenReclaimed,
          double avgReclaimEfficiency
  ) {}

  /**
   * Add a GC listener
   */
  public void addListener(GCListener listener) {
    listeners.add(listener);
  }

  /**
   * Remove a GC listener
   */
  public void removeListener(GCListener listener) {
    listeners.remove(listener);
  }

  /**
   * Clear all GC events
   */
  public void clearEvents() {
    gcEvents.clear();
    totalGcCount.set(0);
    totalGcDuration.set(0);
    totalOldGenReclaimed.set(0);
  }

  /**
   * Notify all listeners of GC event
   */
  private void notifyGCEvent(GCEvent event) {
    for (GCListener listener : listeners) {
      try {
        listener.onGCEvent(event);
      } catch (Exception e) {
        LOG.warn("Error notifying GC listener", e);
      }
    }

    // Forward to MemoryMonitorService if it has registered as listener
    // This is handled through the normal listener mechanism
  }

  /**
   * Notify all listeners of GC alert
   */
  private void notifyGCAlert(GCAlert alert) {
    for (GCListener listener : listeners) {
      try {
        listener.onGCAlert(alert);
      } catch (Exception e) {
        LOG.warn("Error notifying GC listener of alert", e);
      }
    }
  }

  /**
   * Notify all listeners of statistics update
   */
  private void notifyStatisticsUpdated(GCStatistics statistics) {
    for (GCListener listener : listeners) {
      try {
        listener.onGCStatisticsUpdated(statistics);
      } catch (Exception e) {
        LOG.warn("Error notifying GC listener of statistics", e);
      }
    }
  }

  /**
   * Check if a pool is an old generation pool
   */
  private boolean isOldGenPool(String poolName) {
    return poolName.contains("Tenured") ||
            poolName.contains("Old") ||
            poolName.equals("PS Old Gen") ||
            poolName.equals("G1 Old Gen") ||
            poolName.contains("Old Gen");
  }

  /**
   * Check if a pool is a young generation pool
   */
  private boolean isYoungGenPool(String poolName) {
    return poolName.contains("Eden") ||
            poolName.contains("Survivor") ||
            poolName.equals("PS Eden Space") ||
            poolName.equals("PS Survivor Space") ||
            poolName.equals("G1 Eden Space") ||
            poolName.equals("G1 Survivor Space") ||
            poolName.contains("Young");
  }

  /**
   * Format bytes for display
   */
  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  /**
   * Get formatted GC summary
   */
  public String getGCSummary() {
    GCMetrics metrics = getOverallGCMetrics();
    StringBuilder sb = new StringBuilder();
    sb.append("=== GC Summary ===\n");
    sb.append(String.format("Total GC Events: %d\n", metrics.totalGCCount()));
    sb.append(String.format("Full GC Events: %d\n", metrics.fullGCCount()));
    sb.append(String.format("Total GC Time: %d ms\n", metrics.totalGCDuration()));
    sb.append(String.format("Average GC Duration: %.2f ms\n", metrics.avgGCDuration()));
    sb.append(String.format("Max GC Duration: %d ms\n", metrics.maxGCDuration()));
    sb.append(String.format("Total Old Gen Reclaimed: %s\n",
            formatBytes(metrics.totalOldGenReclaimed())));
    sb.append(String.format("Average Reclaim Efficiency: %.1f%%\n",
            metrics.avgReclaimEfficiency()));

    sb.append("\n=== Per Collector Stats ===\n");
    for (GCStatistics stats : gcStatistics.values()) {
      sb.append(String.format("%s: %d GCs, Avg: %.2f ms, Total Reclaimed: %s\n",
              stats.gcName(),
              stats.count(),
              stats.avgDuration(),
              formatBytes(stats.totalOldGenReclaimed())
      ));
    }

    return sb.toString();
  }
}
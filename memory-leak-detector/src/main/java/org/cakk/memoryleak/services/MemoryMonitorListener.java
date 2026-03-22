package org.cakk.memoryleak.services;
// src/main/java/org/cakk/memoryleak/services/MemoryMonitorListener.java
import org.jetbrains.annotations.NotNull;

/**
 * Listener interface for memory monitor events.
 * Implement this interface to receive notifications about memory usage,
 * GC events, and leak detection alerts.
 */
public interface MemoryMonitorListener {

  /**
   * Called when a new memory snapshot is taken.
   * This occurs at regular intervals (every 5 seconds by default).
   *
   * @param snapshot The current memory snapshot containing heap and generation usage
   */
  void onMemoryUpdate(@NotNull MemoryMonitorService.MemorySnapshot snapshot);

  /**
   * Called when a garbage collection event occurs.
   * Provides detailed information about the GC event including duration and memory reclaimed.
   *
   * @param event The GC event details
   */
  void onGcEvent(@NotNull MemoryMonitorService.GcEvent event);

  /**
   * Called when a memory leak is detected.
   * This can be triggered by various patterns:
   * - Consistent old generation growth
   * - Poor GC efficiency
   * - Increasing memory after GC
   *
   * @param alert The leak alert containing severity and details
   */
  void onLeakDetected(@NotNull MemoryMonitorService.LeakAlert alert);

  /**
   * Called when rapid old generation growth is detected.
   * This is a specific alert that may trigger user confirmation for GC.
   *
   * @param alert The rapid growth alert with growth rate and metrics
   */
  void onRapidGrowthDetected(@NotNull MemoryMonitorService.RapidGrowthAlert alert);

  /**
   * Optional: Called when monitoring starts or stops.
   * Can be used to update UI state.
   *
   * @param isMonitoring True if monitoring has started, false if stopped
   */
  default void onMonitoringStateChanged(boolean isMonitoring) {
    // Default empty implementation - override if needed
  }

  /**
   * Optional: Called when GC statistics are updated.
   * Provides aggregated statistics about GC behavior.
   *
   * @param statistics The GC statistics summary
   */
  default void onGCStatisticsUpdated(@NotNull GCListenerService.GCStatistics statistics) {
    // Default empty implementation - override if needed
  }

  /**
   * Optional: Called when a GC alert is triggered.
   * Separate from leak detection, focuses on GC behavior issues.
   *
   * @param alert The GC alert details
   */
  default void onGCAlert(@NotNull GCListenerService.GCAlert alert) {
    // Default empty implementation - override if needed
  }

  /**
   * Optional: Called when the baseline memory is established.
   *
   * @param baselineHeap The baseline heap memory usage
   * @param baselineOldGen The baseline old generation usage
   */
  default void onBaselineEstablished(long baselineHeap, long baselineOldGen) {
    // Default empty implementation - override if needed
  }

  /**
   * Optional: Called when memory usage crosses a threshold.
   * Useful for early warnings before full leak detection.
   *
   * @param heapUsagePercent Current heap usage percentage
   * @param oldGenUsagePercent Current old generation usage percentage
   */
  default void onMemoryThresholdWarning(double heapUsagePercent, double oldGenUsagePercent) {
    // Default empty implementation - override if needed
  }
}
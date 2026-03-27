package org.cakk.memoryleak.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import org.cakk.memoryleak.analysis.AllocationStackAnalyzer;
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

  private final AllocationTracker allocationTracker;
  private final AllocationInterceptor allocationInterceptor;
  private final Project project;
  private final MemoryMXBean memoryBean;
  private final List<MemoryPoolMXBean> memoryPools;
  private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
  private final List<GcEvent> gcEvents = new CopyOnWriteArrayList<>();
  private final List<MemorySnapshot> memorySnapshots = new CopyOnWriteArrayList<>();
  private final List<MemoryMonitorListener> listeners = new CopyOnWriteArrayList<>();

  // Allocation Stack Analyzer
  private final AllocationStackAnalyzer stackAnalyzer;

  // Track allocation samples
  private final Queue<AllocationSample> allocationSamples = new ConcurrentLinkedQueue<>();

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

  // Track last known leak locations
  private volatile List<AllocationStackAnalyzer.LeakLocation> lastKnownLeaks = new ArrayList<>();

  // Detection thresholds
  private static final double WARNING_GROWTH_THRESHOLD = 20.0;
  private static final double HIGH_RISK_GROWTH_THRESHOLD = 50.0;
  private static final double LOW_GC_EFFICIENCY_THRESHOLD = 20.0;
  private static final double CRITICAL_GC_EFFICIENCY_THRESHOLD = 10.0;
  private static final int BASELINE_INCREASE_ALERT_THRESHOLD = 3;

  // Allocation tracking thresholds
  private static final long ALLOCATION_SAMPLE_THRESHOLD = 1024 * 1024; // 1 MB
  private static final int MAX_ALLOCATION_SAMPLES = 100;

  private RemoteMemoryMonitorService remoteMonitorService;
  private boolean isRemoteMode = false;

  // Track heap size
  private long lastHeapSize = 0;

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

  public AllocationTracker getAllocationTracker() {
    return allocationTracker;
  }
  // Allocation sample record
  private static class AllocationSample {
    final String className;
    final String methodName;
    final String fileName;
    final int lineNumber;
    final long size;
    final long timestamp;
    final String stackTrace;

    AllocationSample(String className, String methodName, String fileName,
                     int lineNumber, long size, String stackTrace) {
      this.className = className;
      this.methodName = methodName;
      this.fileName = fileName;
      this.lineNumber = lineNumber;
      this.size = size;
      this.timestamp = System.currentTimeMillis();
      this.stackTrace = stackTrace;
    }
  }

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
    this.stackAnalyzer = new AllocationStackAnalyzer(project);
    this.allocationTracker = new AllocationTracker(project);
    this.allocationInterceptor = new AllocationInterceptor(project);
    this.remoteMonitorService = project.getService(RemoteMemoryMonitorService.class);
    LOG.info("MemoryMonitorService initialized with AllocationInterceptor");
    setupRemoteListener();

    // Start allocation tracking thread
    startAllocationTracking();
  }

  private void setupRemoteListener() {
    if (remoteMonitorService != null) {
      remoteMonitorService.addListener(new RemoteMemoryMonitorService.RemoteMemoryListener() {
        @Override
        public void onMemoryUpdate(RemoteMemoryMonitorService.RemoteMemorySnapshot snapshot) {
          if (isRemoteMode && isMonitoring.get()) {
            // Forward to listeners with remote flag
            MemorySnapshot localSnapshot = convertToLocalSnapshot(snapshot);
            notifyMemoryUpdate(localSnapshot);

            // Check for leaks in remote process
            checkRemoteMemoryLeak(snapshot);
          }
        }

        @Override
        public void onGcEvent(RemoteMemoryMonitorService.GcEvent event) {
          if (isRemoteMode && isMonitoring.get()) {
            // Forward GC event
            GcEvent localEvent = convertToLocalGcEvent(event);
            notifyGcEvent(localEvent);

            // Check if old gen increases after GC (leak pattern)
            if (event.oldGenAfter() > event.oldGenBefore() * 0.9) {
              LOG.warn(String.format(
                      "⚠️ Remote process leak detected! Old gen decreased by only %.1f%% after GC",
                      ((event.oldGenBefore() - event.oldGenAfter()) * 100.0) / event.oldGenBefore()
              ));

              // Trigger leak detection
              ApplicationManager.getApplication().invokeLater(() -> {
                int result = Messages.showYesNoDialog(
                        project,
                        String.format(
                                "⚠️ MEMORY LEAK DETECTED in remote process!\n\n" +
                                        "Process PID: %s\n" +
                                        "GC: %s\n" +
                                        "Old Gen Before: %s\n" +
                                        "Old Gen After: %s\n\n" +
                                        "Objects are surviving GC - this is a classic memory leak.\n\n" +
                                        "Do you want to analyze the leak location?",
                                remoteMonitorService.getConnectedPid(),
                                event.gcName(),
                                formatBytes(event.oldGenBefore()),
                                formatBytes(event.oldGenAfter())
                        ),
                        "Remote Memory Leak Detected",
                        "Analyze",
                        "Ignore",
                        Messages.getWarningIcon()
                );

                if (result == Messages.YES) {
                  triggerRemoteLeakAnalysis();
                }
              });
            }
          }
        }

        @Override
        public void onConnectionStatusChanged(boolean connected, String pid) {
          if (connected) {
            isRemoteMode = true;
            LOG.info("Connected to remote process: " + pid);
            // Reset baseline for remote process
            establishBaseline();
          } else {
            isRemoteMode = false;
            LOG.info("Disconnected from remote process");
          }
        }

        @Override
        public void onError(String error) {
          LOG.error("Remote monitoring error: " + error);
        }
      });
    }
  }

  /**
   * Switch to remote monitoring mode
   */
  public void switchToRemoteMode(String pid) throws Exception {
    if (remoteMonitorService != null && remoteMonitorService.connectToProcess(pid)) {
      LOG.info("Switched to remote monitoring mode for PID: " + pid);
      // Stop local monitoring if running
      if (isMonitoring.get()) {
        stopMonitoring();
      }
      // Start remote monitoring
      startMonitoring();
    }
  }

  /**
   * Switch back to local monitoring
   */
  public void switchToLocalMode() {
    if (remoteMonitorService != null && remoteMonitorService.isConnected()) {
      remoteMonitorService.disconnect();
    }
    isRemoteMode = false;
    LOG.info("Switched to local monitoring mode");
  }

  /**
   * Check remote memory for leak patterns
   */
  private void checkRemoteMemoryLeak(RemoteMemoryMonitorService.RemoteMemorySnapshot snapshot) {
    if (lastHeapSize > 0) {
      long growth = snapshot.heapUsed() - lastHeapSize;

      if (growth > 10 * 1024 * 1024) { // 10MB growth
        LOG.warn(String.format(
                "⚠️ Remote process heap grew by %s! Tracking allocation...",
                formatBytes(growth)
        ));

        // Show notification
        ApplicationManager.getApplication().invokeLater(() -> {
          int result = Messages.showYesNoDialog(
                  project,
                  String.format(
                          "⚠️ Remote process memory is growing rapidly!\n\n" +
                                  "Process PID: %s\n" +
                                  "Heap growth: %s (%.1f MB total)\n" +
                                  "Old Gen: %s\n\n" +
                                  "This may indicate a memory leak.\n\n" +
                                  "Do you want to analyze the leak?",
                          remoteMonitorService.getConnectedPid(),
                          formatBytes(growth),
                          snapshot.heapUsed() / (1024.0 * 1024),
                          formatBytes(snapshot.oldGenUsed())
                  ),
                  "Remote Memory Growth Detected",
                  "Analyze",
                  "Ignore",
                  Messages.getWarningIcon()
          );

          if (result == Messages.YES) {
            triggerRemoteLeakAnalysis();
          }
        });
      }
    }
    lastHeapSize = snapshot.heapUsed();
  }

  /**
   * Trigger leak analysis on remote process
   */
  public void triggerRemoteLeakAnalysis() {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        LOG.info("Starting remote memory leak analysis...");

        // Force GC on remote process
        if (remoteMonitorService != null) {
          remoteMonitorService.forceRemoteGc();
          Thread.sleep(2000);

          // Get memory stats after GC
          RemoteMemoryMonitorService.RemoteMemorySnapshot afterGc =
                  remoteMonitorService.getCurrentSnapshot();

          if (afterGc != null) {
            StringBuilder report = new StringBuilder();
            report.append("\n");
            report.append("╔═══════════════════════════════════════════════════════════════╗\n");
            report.append("║         REMOTE PROCESS MEMORY LEAK ANALYSIS                  ║\n");
            report.append("╚═══════════════════════════════════════════════════════════════╝\n\n");

            report.append(String.format("Process PID: %s\n", remoteMonitorService.getConnectedPid()));
            report.append(String.format("Heap Used: %s\n", formatBytes(afterGc.heapUsed())));
            report.append(String.format("Old Gen Used: %s\n", formatBytes(afterGc.oldGenUsed())));
            report.append(String.format("Young Gen Used: %s\n", formatBytes(afterGc.youngGenUsed())));
            report.append(String.format("Metaspace: %s\n\n", formatBytes(afterGc.metaspaceUsed())));

            // Old gen percentage
            double oldGenPercent = (afterGc.oldGenUsed() * 100.0) / afterGc.heapUsed();
            report.append(String.format("Old Gen Percentage: %.1f%%\n\n", oldGenPercent));

            if (oldGenPercent > 60) {
              report.append("🔴 CRITICAL: High old gen usage (>60%) after GC!\n");
              report.append("💡 This indicates objects are being promoted to old gen and surviving.\n");
              report.append("📌 Likely a memory leak in your application.\n\n");
            } else if (oldGenPercent > 40) {
              report.append("🟡 WARNING: Moderate old gen usage after GC.\n");
              report.append("💡 Monitor closely for memory leak patterns.\n\n");
            } else {
              report.append("✅ Old gen usage looks healthy after GC.\n\n");
            }

            report.append("🔧 RECOMMENDATIONS:\n");
            report.append("1. Review code for:\n");
            report.append("   • Static collections that keep growing\n");
            report.append("   • Event listeners never unregistered\n");
            report.append("   • Caches without eviction policies\n");
            report.append("   • ThreadLocal variables without cleanup\n");
            report.append("2. Use a profiler like VisualVM to analyze heap dumps\n");
            report.append("3. Check for objects that reference each other\n");

            LOG.warn(report.toString());

            // Show to user
            ApplicationManager.getApplication().invokeLater(() -> {
              Messages.showInfoMessage(
                      project,
                      report.toString(),
                      "Remote Memory Leak Analysis"
              );
            });
          }
        }
      } catch (Exception e) {
        LOG.error("Error during remote leak analysis", e);
      }
    });
  }

  /**
   * Convert remote snapshot to local format
   */
  private MemorySnapshot convertToLocalSnapshot(RemoteMemoryMonitorService.RemoteMemorySnapshot remote) {
    return new MemorySnapshot(
            remote.timestamp(),
            remote.heapUsed(),
            remote.heapCommitted(),
            remote.heapMax(),
            remote.oldGenUsed(),
            remote.oldGenCommitted(),
            remote.youngGenUsed(),
            remote.metaspaceUsed()
    );
  }

  /**
   * Convert remote GC event to local format
   */
  private GcEvent convertToLocalGcEvent(RemoteMemoryMonitorService.GcEvent remote) {
    return new GcEvent(
            remote.timestamp(),
            remote.gcName(),
            remote.duration(),
            remote.oldGenBefore(),
            remote.oldGenAfter(),
            remote.oldGenReclaimed()
    );
  }
  public AllocationInterceptor getAllocationInterceptor() {
    return allocationInterceptor;
  }

  // ========== ALLOCATION TRACKING METHODS ==========

  private void startAllocationTracking() {
    scheduler.scheduleAtFixedRate(() -> {
      if (!isMonitoring.get()) return;

      try {
        processAllocationSamples();
        analyzeAllocationPatterns();
      } catch (Exception e) {
        LOG.warn("Error in allocation tracking", e);
      }
    }, 1, 5, TimeUnit.SECONDS);
  }

  private void processAllocationSamples() {
    List<AllocationSample> samples = new ArrayList<>();
    AllocationSample sample;
    while ((sample = allocationSamples.poll()) != null) {
      samples.add(sample);
    }

    if (samples.isEmpty()) return;

    Map<String, Long> aggregatedAllocations = new HashMap<>();
    Map<String, AllocationSample> sampleMap = new HashMap<>();

    for (AllocationSample s : samples) {
      String key = String.format("%s.%s@%s:%d", s.className, s.methodName, s.fileName, s.lineNumber);
      aggregatedAllocations.merge(key, s.size, Long::sum);

      if (!sampleMap.containsKey(key)) {
        sampleMap.put(key, s);
      }
    }

    for (Map.Entry<String, Long> entry : aggregatedAllocations.entrySet()) {
      AllocationSample s = sampleMap.get(entry.getKey());
      if (s != null) {
        stackAnalyzer.trackAllocation(
                s.className, s.methodName, s.fileName, s.lineNumber,
                entry.getValue(), s.stackTrace
        );
      }
    }
  }

  private void analyzeAllocationPatterns() {
    List<AllocationStackAnalyzer.LeakLocation> leaks = stackAnalyzer.getTopLeakSuspects(5);

    if (!leaks.isEmpty() && !leaks.equals(lastKnownLeaks)) {
      lastKnownLeaks = leaks;

      StringBuilder sb = new StringBuilder();
      sb.append("\n═══════════════════════════════════════════════════════════════\n");
      sb.append("📊 ALLOCATION ANALYSIS REPORT\n");
      sb.append("═══════════════════════════════════════════════════════════════\n\n");

      for (AllocationStackAnalyzer.LeakLocation leak : leaks) {
        sb.append("📍 SUSPECT LOCATION:\n");
        sb.append("   File: ").append(leak.fileName).append(":").append(leak.lineNumber).append("\n");
        sb.append("   Class: ").append(leak.className).append("\n");
        sb.append("   Method: ").append(leak.methodName).append("()\n");
        sb.append("   Memory: ").append(formatBytes(leak.totalMemory)).append("\n");
        sb.append("   Instances: ").append(leak.instanceCount).append("\n");
        sb.append("   ─────────────────────────────────────────────────────────\n");
      }

      LOG.warn(sb.toString());

      LeakAlert alert = new LeakAlert(
              System.currentTimeMillis(),
              LeakAlert.Severity.WARNING,
              "Memory allocation leak suspects detected! Check logs for details.",
              Map.of(
                      "topLeak", leaks.get(0).fileName + ":" + leaks.get(0).lineNumber,
                      "leakCount", leaks.size(),
                      "topMemory", leaks.get(0).totalMemory
              )
      );
      notifyLeakDetected(alert);
    }
  }

  public List<AllocationStackAnalyzer.LeakLocation> getAllocationSuspects() {
    return stackAnalyzer.getTopLeakSuspects(10);
  }

  public String getAllocationReport() {
    List<AllocationStackAnalyzer.LeakLocation> leaks = getAllocationSuspects();
    if (leaks.isEmpty()) {
      return "No memory leak suspects detected.";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("\n╔══════════════════════════════════════════════════════════════╗\n");
    sb.append("║         MEMORY ALLOCATION LEAK REPORT                        ║\n");
    sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

    for (int i = 0; i < Math.min(10, leaks.size()); i++) {
      AllocationStackAnalyzer.LeakLocation leak = leaks.get(i);
      sb.append(String.format("%d. %s:%d\n", i + 1, leak.fileName, leak.lineNumber));
      sb.append(String.format("   %s.%s()\n", leak.className, leak.methodName));
      sb.append(String.format("   Memory: %s (%d instances)\n",
              formatBytes(leak.totalMemory), leak.instanceCount));
      sb.append("   \n");
    }

    return sb.toString();
  }

  // ========== MAIN MONITORING METHODS ==========

  public void startMonitoring() {
    if (isMonitoring.compareAndSet(false, true)) {
      LOG.info("Starting memory monitoring with allocation tracking");
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

    List<AllocationStackAnalyzer.LeakLocation> beforeLeaks = getAllocationSuspects();

    System.gc();
    appendLog("[Manual GC] System.gc() called");

    scheduler.schedule(() -> {
      List<AllocationStackAnalyzer.LeakLocation> afterLeaks = getAllocationSuspects();

      if (!afterLeaks.isEmpty()) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n⚠️ OBJECTS SURVIVED GC - POTENTIAL LEAKS ⚠️\n");
        sb.append("═══════════════════════════════════════════════════════════\n");

        for (AllocationStackAnalyzer.LeakLocation leak : afterLeaks) {
          sb.append("📍 ").append(leak.fileName).append(":").append(leak.lineNumber).append("\n");
          sb.append("   ").append(leak.className).append(".").append(leak.methodName).append("()\n");
          sb.append("   Memory: ").append(formatBytes(leak.totalMemory)).append("\n");
          sb.append("   Instances: ").append(leak.instanceCount).append("\n\n");
        }

        LOG.warn(sb.toString());
      }
    }, 2, TimeUnit.SECONDS);
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

    List<GcEvent> recentEvents = gcEvents.subList(gcEvents.size() - Math.min(10, gcEvents.size()), gcEvents.size());

    long firstOldGenAfter = recentEvents.get(0).oldGenAfter();
    long lastOldGenAfter = recentEvents.get(recentEvents.size() - 1).oldGenAfter();
    long baselineTrend = lastOldGenAfter - firstOldGenAfter;
    double baselineTrendMB = baselineTrend / (1024.0 * 1024);

    double avgEfficiency = recentEvents.stream()
            .filter(e -> e.oldGenBefore() > 0)
            .mapToDouble(e -> (double) e.oldGenReclaimed() / e.oldGenBefore() * 100)
            .average()
            .orElse(0);

    boolean hasLeak = false;
    String recommendation;
    Map<String, Object> details = new HashMap<>();

    details.put("baselineTrendMB", baselineTrendMB);
    details.put("averageGcEfficiency", avgEfficiency);
    details.put("gcEventCount", recentEvents.size());
    details.put("firstOldGenAfterMB", firstOldGenAfter / (1024.0 * 1024));
    details.put("lastOldGenAfterMB", lastOldGenAfter / (1024.0 * 1024));

    List<AllocationStackAnalyzer.LeakLocation> suspects = getAllocationSuspects();
    if (!suspects.isEmpty()) {
      details.put("topAllocationSuspect", suspects.get(0).fileName + ":" + suspects.get(0).lineNumber);
      details.put("suspectCount", suspects.size());
    }

    if (baselineTrend > 50 * 1024 * 1024 && avgEfficiency < CRITICAL_GC_EFFICIENCY_THRESHOLD) {
      hasLeak = true;
      recommendation = "CRITICAL: Old gen baseline increased by over 50MB with very poor GC efficiency (<10%). " +
              "This strongly indicates a severe memory leak.\n\n" +
              getSuspectRecommendation(suspects);
      details.put("severity", "CRITICAL");
    } else if (baselineTrend > 10 * 1024 * 1024 && avgEfficiency < LOW_GC_EFFICIENCY_THRESHOLD) {
      hasLeak = true;
      recommendation = "HIGH RISK: Old gen baseline is increasing with poor GC efficiency. " +
              "Memory leak likely.\n\n" +
              getSuspectRecommendation(suspects);
      details.put("severity", "HIGH_RISK");
    } else if (baselineTrend > 5 * 1024 * 1024) {
      hasLeak = true;
      recommendation = "WARNING: Old gen baseline is trending upward. Monitor closely.\n\n" +
              getSuspectRecommendation(suspects);
      details.put("severity", "WARNING");
    } else if (avgEfficiency < LOW_GC_EFFICIENCY_THRESHOLD) {
      recommendation = "Poor GC efficiency detected. Objects are moving to old generation too quickly.\n\n" +
              getSuspectRecommendation(suspects);
      details.put("severity", "INFO");
    } else {
      recommendation = "No memory leak detected. Memory usage patterns are healthy.";
      details.put("severity", "HEALTHY");
    }

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

  private String getSuspectRecommendation(List<AllocationStackAnalyzer.LeakLocation> suspects) {
    if (suspects.isEmpty()) {
      return "• No specific allocation suspects identified. Consider using a profiler.\n";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("📍 SUSPECTED LEAK LOCATIONS:\n");
    for (int i = 0; i < Math.min(3, suspects.size()); i++) {
      AllocationStackAnalyzer.LeakLocation leak = suspects.get(i);
      sb.append(String.format("   %d. %s:%d\n", i + 1, leak.fileName, leak.lineNumber));
      sb.append(String.format("      %s.%s()\n", leak.className, leak.methodName));
      sb.append(String.format("      Memory: %s (%d instances)\n",
              formatBytes(leak.totalMemory), leak.instanceCount));
    }
    return sb.toString();
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

  // ========== PRIVATE METHODS ==========

  private void establishBaseline() {
    LOG.info("Establishing baseline memory usage...");

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

      while (gcEvents.size() > 200) {
        gcEvents.remove(0);
      }

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
    LOG.info("Starting periodic monitoring with allocation tracking...");

    scheduler.scheduleAtFixedRate(() -> {
      if (!isMonitoring.get()) {
        LOG.debug("Monitoring is not active, skipping...");
        return;
      }

      try {
        MemorySnapshot snapshot = getCurrentSnapshot();

        if (memorySnapshots.size() % 5 == 0) {
          allocationInterceptor.analyzeSurvivors();

          LOG.info(String.format("📊 Heap Status: %s (Old Gen: %s, Growth: %+.2f MB from baseline)",
                  formatBytes(snapshot.heapUsed()),
                  formatBytes(snapshot.oldGenUsed()),
                  (snapshot.heapUsed() - baselineUsedHeap) / (1024.0 * 1024)
          ));
        }

        memorySnapshots.add(snapshot);

        if (lastHeapSize > 0) {
          long growth = snapshot.heapUsed() - lastHeapSize;

          if (growth > 0) {
            LOG.info(String.format("📈 Heap growth detected: +%s (from %s to %s)",
                    formatBytes(growth),
                    formatBytes(lastHeapSize),
                    formatBytes(snapshot.heapUsed())
            ));
          }

          if (growth > 1024 * 1024) {
            LOG.warn(String.format("⚠️ SIGNIFICANT HEAP GROWTH: +%s! Tracking allocation...",
                    formatBytes(growth)));

            trackCurrentAllocation();

            List<AllocationTracker.AllocationInfo> suspects =
                    allocationTracker.getTopSuspects(5);

            if (!suspects.isEmpty()) {
              LOG.warn("🚨 POTENTIAL MEMORY LEAK LOCATIONS 🚨");
              for (AllocationTracker.AllocationInfo info : suspects) {
                LOG.warn(String.format("   📍 %s:%d - %s.%s() (%s, %d allocations)",
                        info.fileName != null ? info.fileName : "unknown",
                        info.lineNumber,
                        info.className,
                        info.methodName,
                        formatBytes(info.totalMemory.get()),
                        info.allocationCount.get()));
              }
            } else {
              LOG.warn("⚠️ No allocation suspects found, but heap grew!");
              logCurrentStack();
            }
          }
        }

        allocationTracker.setLastHeapSize(snapshot.heapUsed());
        lastHeapSize = snapshot.heapUsed();

      } catch (Exception e) {
        LOG.error("Error during memory monitoring", e);
      }
    }, 2, 2, TimeUnit.SECONDS);
  }

  private void logCurrentStack() {
    try {
      StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
      LOG.warn("Current stack trace:");
      for (int i = 0; i < Math.min(10, stackTrace.length); i++) {
        LOG.warn("  " + stackTrace[i].toString());
      }
    } catch (Exception e) {
      LOG.debug("Error logging stack", e);
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
                        "trend", "increasing",
                        "suspects", getAllocationSuspects()
                )
        );
        notifyLeakDetected(alert);
      }
    }
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
    if (alert.details().containsKey("suspects")) {
      LOG.warn(getAllocationReport());
    }
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onLeakDetected(alert);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of leak detection", e);
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

  private void notifyMonitoringError(Throwable error, String context) {
    for (MemoryMonitorListener listener : listeners) {
      try {
        listener.onMonitoringError(error, context);
      } catch (Exception e) {
        LOG.warn("Error notifying listener of monitoring error", e);
      }
    }
  }

  private void trackCurrentAllocation() {
    try {
      StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

      for (StackTraceElement element : stackTrace) {
        String className = element.getClassName();
        if (!className.startsWith("java.") &&
                !className.startsWith("javax.") &&
                !className.startsWith("sun.") &&
                !className.startsWith("com.intellij.") &&
                !className.startsWith("org.cakk.")) {

          allocationTracker.trackAllocation(
                  className,
                  element.getMethodName(),
                  element.getFileName(),
                  element.getLineNumber(),
                  0,
                  getStackTraceString(stackTrace)
          );
          break;
        }
      }
    } catch (Exception e) {
      LOG.debug("Error tracking allocation: " + e.getMessage());
    }
  }

  private String getStackTraceString(StackTraceElement[] stackTrace) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < Math.min(10, stackTrace.length); i++) {
      sb.append(stackTrace[i].toString()).append("\n");
    }
    return sb.toString();
  }

  public void forceDetection() {
    LOG.info("Forcing memory leak detection...");

    System.gc();

    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    if (lastHeapSize > 0) {
      long simulatedGrowth = 2 * 1024 * 1024;
      LOG.warn(String.format("SIMULATED: Heap grew by %s", formatBytes(simulatedGrowth)));

      trackCurrentAllocation();

      List<AllocationTracker.AllocationInfo> suspects =
              allocationTracker.getTopSuspects(5);

      if (!suspects.isEmpty()) {
        LOG.warn("🚨 POTENTIAL MEMORY LEAK LOCATIONS 🚨");
        for (AllocationTracker.AllocationInfo info : suspects) {
          LOG.warn(String.format("   📍 %s:%d - %s.%s() (%s)",
                  info.fileName, info.lineNumber,
                  info.className, info.methodName,
                  formatBytes(info.totalMemory.get())));
        }
      } else {
        LOG.warn("No allocation suspects found");
        logCurrentStack();
      }
    }
  }

  public void forceLeakDetection() {
    LOG.info("Forcing memory leak detection...");

    System.gc();

    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    allocationInterceptor.analyzeSurvivors();

    List<AllocationInterceptor.AllocationInfo> suspects =
            allocationInterceptor.getTopSuspects(10);

    if (!suspects.isEmpty()) {
      StringBuilder report = new StringBuilder();
      report.append("\n🔍 MEMORY LEAK SUSPECTS 🔍\n");
      report.append("═══════════════════════════════════════════════════════════\n");

      for (AllocationInterceptor.AllocationInfo info : suspects) {
        report.append(String.format("📍 %s:%d\n", info.fileName, info.lineNumber));
        report.append(String.format("   %s.%s()\n", info.className, info.methodName));
        report.append(String.format("   Memory: %s (%d allocations)\n",
                formatBytes(info.totalMemory.get()), info.allocationCount.get()));
        report.append(String.format("   Surviving objects: %d\n", info.getSurvivingObjects()));
        report.append("\n");
      }

      LOG.warn(report.toString());
    } else {
      LOG.info("No memory leak suspects found");
    }
  }
}
package org.cakk.memoryleak.services;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Intercepts and tracks object allocations
 */
public class AllocationInterceptor {

  private static final Logger LOG = Logger.getInstance(AllocationInterceptor.class);

  private final Project project;
  private final Map<String, AllocationInfo> trackedAllocations = new ConcurrentHashMap<>();
  private final Map<Object, AllocationInfo> objectToAllocation = new WeakHashMap<>();

  // Track large object creations
  private static final long TRACK_THRESHOLD = 100 * 1024; // 100KB

  public AllocationInterceptor(Project project) {
    this.project = project;
  }

  /**
   * Track an object allocation - call this from user code
   * This is the key method - users need to call this in suspicious code
   */
  public void trackObjectCreation(Object obj, String className, String methodName,
                                  String fileName, int lineNumber, long estimatedSize) {
    if (obj == null) return;

    String key = String.format("%s.%s@%s:%d", className, methodName, fileName, lineNumber);

    AllocationInfo info = trackedAllocations.computeIfAbsent(key, k ->
            new AllocationInfo(className, methodName, fileName, lineNumber)
    );

    info.addAllocation(obj, estimatedSize);

    if (estimatedSize > TRACK_THRESHOLD) {
      LOG.warn(String.format("Large object created: %s at %s:%d (%s.%s()) - Size: %s",
              obj.getClass().getSimpleName(),
              fileName, lineNumber,
              className, methodName,
              formatBytes(estimatedSize)
      ));
    }

    // Store weak reference to track if object survives GC
    synchronized (objectToAllocation) {
      objectToAllocation.put(obj, info);
    }
  }

  /**
   * Called after GC to see what objects survived
   */
  public void analyzeSurvivors() {
    // Clean up dead references first
    synchronized (objectToAllocation) {
      objectToAllocation.entrySet().removeIf(entry -> entry.getKey() == null);
    }

    // Objects that survived GC (still in objectToAllocation)
    // This is a sign of memory leak
    if (!objectToAllocation.isEmpty()) {
      Map<AllocationInfo, Integer> survivors = new HashMap<>();

      synchronized (objectToAllocation) {
        for (Map.Entry<Object, AllocationInfo> entry : objectToAllocation.entrySet()) {
          if (entry.getKey() != null) {
            survivors.merge(entry.getValue(), 1, Integer::sum);
          }
        }
      }

      if (!survivors.isEmpty()) {
        LOG.warn("⚠️ OBJECTS SURVIVED GC - POTENTIAL MEMORY LEAK ⚠️");
        for (Map.Entry<AllocationInfo, Integer> entry : survivors.entrySet()) {
          AllocationInfo info = entry.getKey();
          int count = entry.getValue();
          LOG.warn(String.format("   📍 %s:%d - %s.%s() (%d objects, %s total)",
                  info.fileName != null ? info.fileName : "unknown",
                  info.lineNumber,
                  info.className != null ? info.className : "unknown",
                  info.methodName != null ? info.methodName : "unknown",
                  count,
                  formatBytes(info.totalMemory.get())
          ));
        }
      }
    }
  }

  public List<AllocationInfo> getTopSuspects(int limit) {
    return trackedAllocations.values().stream()
            .sorted((a, b) -> Long.compare(b.totalMemory.get(), a.totalMemory.get()))
            .limit(limit)
            .collect(Collectors.toList());
  }

  /**
   * Clear all tracked data
   */
  public void clear() {
    synchronized (trackedAllocations) {
      trackedAllocations.clear();
    }
    synchronized (objectToAllocation) {
      objectToAllocation.clear();
    }
  }

  /**
   * Get total tracked memory
   */
  public long getTotalTrackedMemory() {
    return trackedAllocations.values().stream()
            .mapToLong(info -> info.totalMemory.get())
            .sum();
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  /**
   * Information about allocations at a specific location
   */
  public static class AllocationInfo {
    public final String className;
    public final String methodName;
    public final String fileName;
    public final int lineNumber;
    public final AtomicLong totalMemory;
    public final AtomicLong allocationCount;
    public final List<WeakReference<Object>> objects;

    public AllocationInfo(String className, String methodName, String fileName, int lineNumber) {
      this.className = className != null ? className : "unknown";
      this.methodName = methodName != null ? methodName : "unknown";
      this.fileName = fileName != null ? fileName : "unknown";
      this.lineNumber = lineNumber;
      this.totalMemory = new AtomicLong(0);
      this.allocationCount = new AtomicLong(0);
      this.objects = new ArrayList<>();
    }

    public void addAllocation(Object obj, long size) {
      totalMemory.addAndGet(size);
      allocationCount.incrementAndGet();

      synchronized (objects) {
        objects.add(new WeakReference<>(obj));
        // Clean up dead references periodically
        if (objects.size() > 1000) {
          objects.removeIf(ref -> ref.get() == null);
        }
      }
    }

    public int getSurvivingObjects() {
      synchronized (objects) {
        objects.removeIf(ref -> ref.get() == null);
        return objects.size();
      }
    }

    @Override
    public String toString() {
      return String.format("%s:%d - %s.%s() (%s, %d allocations)",
              fileName, lineNumber, className, methodName,
              formatBytes(totalMemory.get()), allocationCount.get());
    }

    private String formatBytes(long bytes) {
      if (bytes < 1024) return bytes + " B";
      if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
      if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
      return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
  }
}
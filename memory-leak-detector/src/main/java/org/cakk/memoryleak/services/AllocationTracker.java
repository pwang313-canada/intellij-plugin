package org.cakk.memoryleak.services;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks allocations and identifies leak locations
 */
public class AllocationTracker {

  private static final Logger LOG = Logger.getInstance(AllocationTracker.class);

  private final Project project;
  private final Map<String, AllocationInfo> allocations = new ConcurrentHashMap<>();
  private final AtomicLong totalTrackedMemory = new AtomicLong(0);

  // Track when we last saw growth
  private long lastGrowthTime = System.currentTimeMillis();
  private long lastHeapSize = 0;

  public AllocationTracker(Project project) {
    this.project = project;
  }

  /**
   * Track a new allocation with full stack trace
   */
  public void trackAllocationWithStack(String className, String methodName,
                                       String fileName, int lineNumber,
                                       long size, String stackTrace,
                                       StackTraceElement[] fullStackTrace) {
    String key = String.format("%s.%s@%s:%d", className, methodName, fileName, lineNumber);

    AllocationInfo info = allocations.computeIfAbsent(key, k ->
            new AllocationInfo(className, methodName, fileName, lineNumber)
    );

    info.addAllocation(size, stackTrace);
    info.addFullStackTrace(fullStackTrace); // Now this method exists
    totalTrackedMemory.addAndGet(size);

    // Log large allocations with location
    if (size > 1024 * 1024) {
      LOG.warn(String.format("Large allocation: at %s:%d (%s.%s()) - Size: %s\nStack: %s",
              fileName, lineNumber,
              className, methodName,
              formatBytes(size),
              getFirstNonSystemFrame(fullStackTrace)
      ));
    }
  }

  /**
   * Track a new allocation - call this from your MemoryMonitorService when heap grows
   */
  public void trackAllocation(String className, String methodName,
                              String fileName, int lineNumber,
                              long size, String stackTrace) {
    String key = String.format("%s.%s@%s:%d", className, methodName, fileName, lineNumber);

    AllocationInfo info = allocations.computeIfAbsent(key, k ->
            new AllocationInfo(className, methodName, fileName, lineNumber)
    );

    info.addAllocation(size, stackTrace);
    totalTrackedMemory.addAndGet(size);

    // Log every allocation over 1MB
    if (size > 1024 * 1024) {
      LOG.warn(String.format("Large allocation: at %s:%d (%s.%s()) - Size: %s",
              fileName, lineNumber, className, methodName, formatBytes(size)));
    }
  }

  private String getFirstNonSystemFrame(StackTraceElement[] stackTrace) {
    for (StackTraceElement element : stackTrace) {
      String className = element.getClassName();
      if (!className.startsWith("java.") &&
              !className.startsWith("javax.") &&
              !className.startsWith("sun.") &&
              !className.startsWith("com.intellij.") &&
              !className.startsWith("org.cakk.")) {
        return String.format("%s.%s(%s:%d)",
                className, element.getMethodName(),
                element.getFileName(), element.getLineNumber());
      }
    }
    return "Unknown";
  }

  /**
   * Get the top allocation suspects sorted by memory usage
   */
  public List<AllocationInfo> getTopSuspects(int limit) {
    return allocations.values().stream()
            .sorted((a, b) -> Long.compare(b.totalMemory.get(), a.totalMemory.get()))
            .limit(limit)
            .collect(java.util.stream.Collectors.toList());
  }

  /**
   * Get a detailed report of all tracked allocations
   */
  public String getAllocationReport() {
    List<AllocationInfo> suspects = getTopSuspects(20);
    if (suspects.isEmpty()) {
      return "No allocations tracked yet.";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("\n╔══════════════════════════════════════════════════════════════╗\n");
    sb.append("║         MEMORY ALLOCATION REPORT                             ║\n");
    sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");
    sb.append(String.format("Total Tracked Memory: %s\n\n", formatBytes(totalTrackedMemory.get())));

    for (int i = 0; i < Math.min(10, suspects.size()); i++) {
      AllocationInfo info = suspects.get(i);
      sb.append(String.format("%d. %s:%d\n", i + 1, info.fileName, info.lineNumber));
      sb.append(String.format("   %s.%s()\n", info.className, info.methodName));
      sb.append(String.format("   Memory: %s (%d allocations)\n",
              formatBytes(info.totalMemory.get()), info.allocationCount.get()));
      sb.append(String.format("   Surviving objects: %d\n", info.getSurvivingObjects()));

      // Show survival rate
      double survivalRate = info.getSurvivalRate();
      if (survivalRate > 50) {
        sb.append(String.format("   🔴 HIGH RISK: %.1f%% survived GC!\n", survivalRate));
      } else if (survivalRate > 20) {
        sb.append(String.format("   🟡 MEDIUM RISK: %.1f%% survived GC.\n", survivalRate));
      }

      // Show the stack trace snippet
      if (!info.stackTraces.isEmpty()) {
        String[] lines = info.stackTraces.get(0).split("\n");
        if (lines.length > 1) {
          sb.append("   Stack: ").append(lines[1].trim()).append("\n");
        }
      }
      sb.append("\n");
    }

    return sb.toString();
  }

  /**
   * Find the actual PSI element for navigation
   */
  public PsiElement findPsiElement(String className, String methodName,
                                   String fileName, int lineNumber) {
    try {
      JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
      GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

      // Find the class
      PsiClass psiClass = psiFacade.findClass(className, scope);
      if (psiClass == null) return null;

      // Find the method
      for (PsiMethod method : psiClass.getMethods()) {
        if (method.getName().equals(methodName)) {
          return method;
        }
      }
    } catch (Exception e) {
      LOG.debug("Error finding PSI element: " + e.getMessage());
    }
    return null;
  }

  public void clear() {
    allocations.clear();
    totalTrackedMemory.set(0);
  }

  public void setLastHeapSize(long heapSize) {
    if (lastHeapSize > 0 && heapSize > lastHeapSize) {
      long growth = heapSize - lastHeapSize;
      if (growth > 10 * 1024 * 1024) { // Growth > 10MB
        LOG.warn(String.format("⚠️ Heap grew by %s! Checking allocations...", formatBytes(growth)));
        // Log the top suspects when heap grows
        LOG.warn(getAllocationReport());
      }
    }
    this.lastHeapSize = heapSize;
    this.lastGrowthTime = System.currentTimeMillis();
  }

  public long getLastHeapSize() {
    return lastHeapSize;
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
    public final AtomicLong totalMemory = new AtomicLong(0);
    public final AtomicLong allocationCount = new AtomicLong(0);
    public final List<String> stackTraces = new ArrayList<>();
    public final List<WeakReference<Object>> objectReferences = new ArrayList<>();
    public final List<String> fullStackTraces = new ArrayList<>(); // Store full stack traces

    public AllocationInfo(String className, String methodName, String fileName, int lineNumber) {
      this.className = className != null ? className : "unknown";
      this.methodName = methodName != null ? methodName : "unknown";
      this.fileName = fileName != null ? fileName : "unknown";
      this.lineNumber = lineNumber;
    }

    public void addAllocation(long size, String stackTrace) {
      totalMemory.addAndGet(size);
      allocationCount.incrementAndGet();

      // Keep only first 3 stack traces for debugging
      if (stackTraces.size() < 3) {
        stackTraces.add(stackTrace);
      }
    }

    /**
     * Add full stack trace for this allocation
     */
    public void addFullStackTrace(StackTraceElement[] stackTrace) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < Math.min(20, stackTrace.length); i++) {
        sb.append(stackTrace[i].toString()).append("\n");
      }

      // Keep only first 5 full stack traces
      if (fullStackTraces.size() < 5) {
        fullStackTraces.add(sb.toString());
      }
    }

    /**
     * Add an object reference to track if it survives GC
     */
    public void addObjectReference(Object obj) {
      if (obj != null) {
        synchronized (objectReferences) {
          objectReferences.add(new WeakReference<>(obj));
          // Clean up dead references periodically
          if (objectReferences.size() > 1000) {
            objectReferences.removeIf(ref -> ref.get() == null);
          }
        }
      }
    }

    /**
     * Get the number of objects that survived GC
     * This is a key indicator of memory leaks
     */
    public int getSurvivingObjects() {
      synchronized (objectReferences) {
        // Remove dead references first
        objectReferences.removeIf(ref -> ref.get() == null);
        return objectReferences.size();
      }
    }

    /**
     * Get survival rate percentage
     */
    public double getSurvivalRate() {
      long count = allocationCount.get();
      if (count == 0) return 0;
      return (getSurvivingObjects() * 100.0) / count;
    }

    /**
     * Get the first non-system stack trace element
     */
    public String getFirstUserStackTrace() {
      for (String trace : stackTraces) {
        String[] lines = trace.split("\n");
        for (String line : lines) {
          if (!line.contains("java.") && !line.contains("javax.") &&
                  !line.contains("sun.") && !line.contains("com.intellij.")) {
            return line.trim();
          }
        }
      }
      return "Unknown";
    }

    @Override
    public String toString() {
      return String.format("%s:%d - %s.%s() (%s, %d allocations, %d survivors, %.1f%% survival)",
              fileName, lineNumber, className, methodName,
              formatBytes(totalMemory.get()), allocationCount.get(),
              getSurvivingObjects(), getSurvivalRate());
    }

    private String formatBytes(long bytes) {
      if (bytes < 1024) return bytes + " B";
      if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
      if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
      return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
  }
}
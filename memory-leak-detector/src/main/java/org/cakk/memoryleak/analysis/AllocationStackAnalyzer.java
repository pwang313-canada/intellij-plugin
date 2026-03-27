package org.cakk.memoryleak.analysis;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.sun.management.HotSpotDiagnosticMXBean;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Analyzes memory allocation patterns to identify potential memory leaks
 * with precise file and line number information.
 */
public class AllocationStackAnalyzer {
    
    private static final Logger LOG = Logger.getInstance(AllocationStackAnalyzer.class);
    
    private final Project project;
    private final Map<String, LeakLocation> leakSuspects;
    private final Map<String, AllocationStats> allocationStats;
    private final AtomicLong totalAllocations;
    
    // Configuration thresholds
    private static final long DEFAULT_SUSPECT_THRESHOLD = 10_000_000; // 10 MB
    private static final int DEFAULT_INSTANCE_THRESHOLD = 1000; // 1000 instances
    
    public AllocationStackAnalyzer(Project project) {
        this.project = project;
        this.leakSuspects = new ConcurrentHashMap<>();
        this.allocationStats = new ConcurrentHashMap<>();
        this.totalAllocations = new AtomicLong(0);
        
        // Start monitoring thread
        startMonitoringThread();
    }
    
    /**
     * Leak location data class with precise file and line information
     */
    public static class LeakLocation {
        public final String className;
        public final String methodName;
        public final String fileName;
        public final int lineNumber;
        public final long totalMemory;
        public final int instanceCount;
        public final String stackTrace;
        public final PsiElement psiElement;
        
        public LeakLocation(String className, String methodName, 
                           String fileName, int lineNumber,
                           long totalMemory, int instanceCount,
                           String stackTrace, PsiElement psiElement) {
            this.className = className;
            this.methodName = methodName;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
            this.totalMemory = totalMemory;
            this.instanceCount = instanceCount;
            this.stackTrace = stackTrace;
            this.psiElement = psiElement;
        }
        
        @Override
        public String toString() {
            return String.format("%s:%d - %s.%s() (Memory: %s, Instances: %d)",
                fileName, lineNumber, className, methodName, 
                formatBytes(totalMemory), instanceCount);
        }
        
        private static String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
    
    /**
     * Internal class for tracking allocation statistics
     */
    private static class AllocationStats {
        final String className;
        final String methodName;
        final String fileName;
        final int lineNumber;
        final AtomicLong allocatedMemory = new AtomicLong(0);
        final AtomicLong instanceCount = new AtomicLong(0);
        final List<String> stackTraces = new ArrayList<>();
        long lastUpdateTime = System.currentTimeMillis();
        
        AllocationStats(String className, String methodName, 
                       String fileName, int lineNumber) {
            this.className = className;
            this.methodName = methodName;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
        }
        
        void addAllocation(long size, String stackTrace) {
            allocatedMemory.addAndGet(size);
            instanceCount.incrementAndGet();
            if (stackTraces.size() < 10) { // Keep last 10 stack traces
                stackTraces.add(stackTrace);
            }
            lastUpdateTime = System.currentTimeMillis();
        }
        
        boolean isActive() {
            return (System.currentTimeMillis() - lastUpdateTime) < 30000; // Active in last 30 seconds
        }
        
        LeakLocation toLeakLocation(PsiElement element) {
            return new LeakLocation(
                className, methodName, fileName, lineNumber,
                allocatedMemory.get(), (int) instanceCount.get(),
                stackTraces.isEmpty() ? "" : stackTraces.get(0),
                element
            );
        }
    }
    
    /**
     * Start background monitoring thread
     */
    private void startMonitoringThread() {
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000); // Analyze every 5 seconds
                    analyzeAllocationPatterns();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOG.warn("Error in allocation monitoring", e);
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.setName("Memory-Leak-Monitor");
        monitorThread.start();
    }
    
    /**
     * Analyze allocation patterns to detect leaks
     */
    private void analyzeAllocationPatterns() {
        // Clean up old entries that are no longer active
        allocationStats.entrySet().removeIf(entry -> !entry.getValue().isActive());
        
        // Check for suspicious allocation patterns
        for (AllocationStats stats : allocationStats.values()) {
            if (stats.allocatedMemory.get() > DEFAULT_SUSPECT_THRESHOLD ||
                stats.instanceCount.get() > DEFAULT_INSTANCE_THRESHOLD) {
                
                String key = getKey(stats.className, stats.methodName, 
                                   stats.fileName, stats.lineNumber);
                if (!leakSuspects.containsKey(key)) {
                    // Find PSI element for this location
                    PsiElement element = findPsiElement(
                        stats.className, stats.methodName, 
                        stats.fileName, stats.lineNumber);
                    
                    LeakLocation leak = stats.toLeakLocation(element);
                    leakSuspects.put(key, leak);
                    
                    LOG.warn("Potential memory leak detected at: {}:{} - {} MB allocated"
                    );
                }
            }
        }
    }
    
    /**
     * Track a new allocation
     */
    public void trackAllocation(String className, String methodName,
                               String fileName, int lineNumber,
                               long size, String stackTrace) {
        String key = getKey(className, methodName, fileName, lineNumber);
        AllocationStats stats = allocationStats.computeIfAbsent(
            key, k -> new AllocationStats(className, methodName, fileName, lineNumber)
        );
        stats.addAllocation(size, stackTrace);
        totalAllocations.addAndGet(size);
    }
    
    /**
     * Get top leak suspects
     */
    public List<LeakLocation> getTopLeakSuspects(int limit) {
        return leakSuspects.values().stream()
            .sorted((a, b) -> Long.compare(b.totalMemory, a.totalMemory))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * Find PSI element at a specific location
     */
    @Nullable
    public PsiElement findPsiElement(String className, String methodName,
                                     String fileName, int lineNumber) {
        try {
            // Find the class
            PsiClass psiClass = findClass(className);
            if (psiClass == null) {
                LOG.debug("Class not found: {}", className);
                return null;
            }
            
            // Find the method
            PsiMethod psiMethod = findMethod(psiClass, methodName);
            if (psiMethod == null) {
                LOG.debug("Method not found: {} in class {}", methodName, className);
                return null;
            }
            
            // Get the method body
            PsiCodeBlock body = psiMethod.getBody();
            if (body == null) {
                return psiMethod; // Return the method itself if no body
            }
            
            // Find the specific line in the method
            return findElementAtLine(body, lineNumber);
            
        } catch (Exception e) {
            LOG.debug("Error finding PSI element: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Find class by name
     */
    @Nullable
    private PsiClass findClass(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        
        JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        
        // Try to find the class
        PsiClass[] classes = psiFacade.findClasses(className, scope);
        if (classes.length > 0) {
            return classes[0];
        }
        
        // Try with simple name
        String simpleName = className.contains(".") ? 
            className.substring(className.lastIndexOf('.') + 1) : className;
        
        return psiFacade.findClass(simpleName, scope);
    }
    
    /**
     * Find method by name in class
     */
    @Nullable
    private PsiMethod findMethod(PsiClass psiClass, String methodName) {
        if (methodName == null) {
            return null;
        }
        
        // First try exact match
        for (PsiMethod method : psiClass.getMethods()) {
            if (methodName.equals(method.getName())) {
                return method;
            }
        }
        
        // Try to find by checking if methodName contains the actual method name
        for (PsiMethod method : psiClass.getMethods()) {
            if (methodName.contains(method.getName())) {
                return method;
            }
        }
        
        return null;
    }
    
    /**
     * Find PSI element at specific line number within a code block
     */
    @Nullable
    private PsiElement findElementAtLine(PsiElement element, int lineNumber) {
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return null;
        }
        
        com.intellij.openapi.editor.Document document = 
            com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file);
        
        if (document == null) {
            return null;
        }
        
        // Convert to 0-based line number
        int targetLine = lineNumber - 1;
        if (targetLine < 0 || targetLine >= document.getLineCount()) {
            return null;
        }
        
        int lineStartOffset = document.getLineStartOffset(targetLine);
        int lineEndOffset = document.getLineEndOffset(targetLine);
        
        // Find the smallest element that covers this line
        return findElementAtOffset(element, lineStartOffset, lineEndOffset);
    }
    
    /**
     * Find PSI element at specific offset
     */
    @Nullable
    private PsiElement findElementAtOffset(PsiElement root, int startOffset, int endOffset) {
        PsiElement result = null;
        PsiElement current = root;
        
        // Traverse the PSI tree to find the element that contains the offset
        while (current != null) {
            int textOffset = current.getTextOffset();
            int textLength = current.getTextLength();
            
            if (textOffset <= startOffset && (textOffset + textLength) >= startOffset) {
                result = current;
                
                // Try to find a more specific child element
                PsiElement child = findChildAtOffset(current, startOffset);
                if (child != null && child != current) {
                    current = child;
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        
        return result;
    }
    
    /**
     * Find child element at specific offset
     */
    @Nullable
    private PsiElement findChildAtOffset(PsiElement parent, int offset) {
        for (PsiElement child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            int childStart = child.getTextOffset();
            int childEnd = childStart + child.getTextLength();
            
            if (childStart <= offset && childEnd > offset) {
                return child;
            }
        }
        return null;
    }
    
    /**
     * Get heap dump analysis
     */
    public HeapAnalysisResult analyzeHeapDump() {
        HeapAnalysisResult result = new HeapAnalysisResult();
        
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            HotSpotDiagnosticMXBean diagnosticBean = 
                ManagementFactory.newPlatformMXBeanProxy(
                    server, 
                    "com.sun.management:type=HotSpotDiagnostic",
                    HotSpotDiagnosticMXBean.class);
            
            // Get heap memory usage
            Runtime runtime = Runtime.getRuntime();
            result.totalHeapMemory = runtime.totalMemory();
            result.freeHeapMemory = runtime.freeMemory();
            result.usedHeapMemory = result.totalHeapMemory - result.freeHeapMemory;
            result.maxHeapMemory = runtime.maxMemory();
            
            // Get GC information
            result.gcCount = getGcCount();
            result.gcTime = getGcTime();
            
        } catch (Exception e) {
            LOG.warn("Failed to analyze heap dump", e);
        }
        
        return result;
    }
    
    /**
     * Get GC count from management beans
     */
    private long getGcCount() {
        long gcCount = 0;
        for (java.lang.management.GarbageCollectorMXBean gcBean : 
             ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gcBean.getCollectionCount();
        }
        return gcCount;
    }
    
    /**
     * Get GC time from management beans
     */
    private long getGcTime() {
        long gcTime = 0;
        for (java.lang.management.GarbageCollectorMXBean gcBean : 
             ManagementFactory.getGarbageCollectorMXBeans()) {
            gcTime += gcBean.getCollectionTime();
        }
        return gcTime;
    }
    
    /**
     * Clear all tracking data
     */
    public void clear() {
        allocationStats.clear();
        leakSuspects.clear();
        totalAllocations.set(0);
    }
    
    /**
     * Get statistics summary
     */
    public String getStatisticsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Allocation Statistics:\n");
        sb.append("Total Allocations: ").append(formatBytes(totalAllocations.get())).append("\n");
        sb.append("Active Tracked Locations: ").append(allocationStats.size()).append("\n");
        sb.append("Potential Leaks Detected: ").append(leakSuspects.size()).append("\n");
        
        if (!leakSuspects.isEmpty()) {
            sb.append("\nTop Leak Locations:\n");
            getTopLeakSuspects(5).forEach(leak -> {
                sb.append("  - ").append(leak.fileName).append(":").append(leak.lineNumber)
                  .append(" (").append(formatBytes(leak.totalMemory)).append(")\n");
            });
        }
        
        return sb.toString();
    }
    
    /**
     * Get key for maps
     */
    private String getKey(String className, String methodName, 
                         String fileName, int lineNumber) {
        return String.format("%s.%s@%s:%d", className, methodName, fileName, lineNumber);
    }
    
    /**
     * Format bytes to human-readable string
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * Heap analysis result class
     */
    public static class HeapAnalysisResult {
        public long totalHeapMemory;
        public long freeHeapMemory;
        public long usedHeapMemory;
        public long maxHeapMemory;
        public long gcCount;
        public long gcTime;
        
        public double getHeapUsagePercent() {
            return (usedHeapMemory * 100.0) / maxHeapMemory;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Heap: %s / %s (%.1f%%), GC: %d collections, %d ms",
                formatBytes(usedHeapMemory),
                formatBytes(maxHeapMemory),
                getHeapUsagePercent(),
                gcCount,
                gcTime
            );
        }
        
        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
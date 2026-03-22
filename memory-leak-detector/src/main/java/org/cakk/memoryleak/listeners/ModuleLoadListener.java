// src/main/java/org/cakk/memoryleak/listeners/ModuleLoadListener.java
package org.cakk.memoryleak.listeners;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Module tracking without using ModuleListener interface
 * This uses ProjectManagerListener to track modules when projects open/close
 */
public class ModuleLoadListener implements StartupActivity, ProjectManagerListener {

  private final ConcurrentMap<String, ModuleInfo> moduleInfoMap = new ConcurrentHashMap<>();

  @Override
  public void runActivity(@NotNull Project project) {
    // Called when project is opened
    System.out.println("[MemoryLeakDetector] Project opened: " + project.getName());

    // Track all existing modules
    ModuleManager moduleManager = ModuleManager.getInstance(project);
    Module[] modules = moduleManager.getModules();
    for (Module module : modules) {
      trackModule(module);
    }
  }

  @Override
  public void projectClosing(@NotNull Project project) {
    System.out.println("[MemoryLeakDetector] Project closing: " + project.getName());

    // Clean up module info for this project
    moduleInfoMap.clear();
  }

  private void trackAllModules(Project project) {
    ModuleManager moduleManager = ModuleManager.getInstance(project);
    Module[] modules = moduleManager.getModules();
    for (Module module : modules) {
      trackModule(module);
    }
  }

  private void trackModule(Module module) {
    ModuleInfo info = new ModuleInfo(
            module.getName(),
            System.currentTimeMillis(),
            module.getProject().getName()
    );
    moduleInfoMap.put(module.getName(), info);
    System.out.println("[MemoryLeakDetector] Tracking module: " + module.getName());
  }

  private void untrackModule(Module module) {
    moduleInfoMap.remove(module.getName());
    System.out.println("[MemoryLeakDetector] Untracking module: " + module.getName());
  }

  public ModuleInfo getModuleInfo(String moduleName) {
    return moduleInfoMap.get(moduleName);
  }

  public static class ModuleInfo {
    private final String name;
    private final long addedTimestamp;
    private final String projectName;

    public ModuleInfo(String name, long addedTimestamp, String projectName) {
      this.name = name;
      this.addedTimestamp = addedTimestamp;
      this.projectName = projectName;
    }

    public String getName() {
      return name;
    }

    public long getAddedTimestamp() {
      return addedTimestamp;
    }

    public String getProjectName() {
      return projectName;
    }

    public long getAge() {
      return System.currentTimeMillis() - addedTimestamp;
    }
  }
}
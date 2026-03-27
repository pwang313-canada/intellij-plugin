// src/main/java/org/cakk/unusedcode/ui/UnusedCodeToolWindow.java
package org.cakk.unusedcode.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.cakk.unusedcode.models.DuplicateImport;
import org.cakk.unusedcode.models.UnusedClass;
import org.cakk.unusedcode.models.UnusedImport;
import org.cakk.unusedcode.models.UnusedMethod;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tool window service for displaying unused code results
 */
@Service
public class UnusedCodeToolWindow {

  public static final Key<UnusedCodeToolWindow> KEY = Key.create("UnusedCodeToolWindow");

  private final Project project;
  private final UnusedCodeToolWindowPanel panel;
  private boolean initialized = false;

  public UnusedCodeToolWindow(@NotNull Project project) {
    this.project = project;
    this.panel = new UnusedCodeToolWindowPanel(project);
    initToolWindow();
  }

  /**
   * Get or create the tool window instance for the project
   */
  public static UnusedCodeToolWindow getInstance(@NotNull Project project) {
    UnusedCodeToolWindow instance = project.getUserData(KEY);
    if (instance == null) {
      instance = new UnusedCodeToolWindow(project);
      project.putUserData(KEY, instance);
    }
    return instance;
  }

  /**
   * Initialize the tool window content
   */
  private void initToolWindow() {
    if (initialized) return;

    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
    ToolWindow toolWindow = toolWindowManager.getToolWindow("Unused Code Detector");

    if (toolWindow != null) {
      // Create content
      ContentFactory contentFactory = ContentFactory.getInstance();
      Content content = contentFactory.createContent(panel.getPanel(), "Results", false);
      toolWindow.getContentManager().addContent(content);
      initialized = true;
    }
  }

  /**
   * Show the tool window
   */
  public void show() {
    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
    ToolWindow toolWindow = toolWindowManager.getToolWindow("Unused Code Detector");
    if (toolWindow != null) {
      toolWindow.show();
      // Ensure the content is selected
      if (toolWindow.getContentManager().getContentCount() > 0) {
        toolWindow.getContentManager().setSelectedContent(
                toolWindow.getContentManager().getContent(0), true
        );
      }
    } else {
      System.err.println("Tool window 'Unused Code Detector' not found!");
    }
  }

  /**
   * Hide the tool window
   */
  public void hide() {
    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
    ToolWindow toolWindow = toolWindowManager.getToolWindow("Unused Code Detector");
    if (toolWindow != null && toolWindow.isVisible()) {
      toolWindow.hide();
    }
  }

  /**
   * Clear all results from the tool window
   */
  public void clearResults() {
    panel.clearResults();
  }

  /**
   * Set the analysis results to display
   */
  public void setResults(List<UnusedClass> classes,
                         List<UnusedMethod> methods,
                         List<UnusedImport> imports,
                         List<DuplicateImport> duplicates) {
    System.out.println("=== UnusedCodeToolWindow.setResults ===");
    System.out.println("Classes: " + classes.size());
    System.out.println("Methods: " + methods.size());
    System.out.println("Imports: " + imports.size());
    System.out.println("Duplicates: " + duplicates.size());

    // Print each duplicate
    for (DuplicateImport dup : duplicates) {
      System.out.println("  Duplicate: " + dup.getImportText() +
              " (lines: " + dup.getLineNumbers() + ")");
    }

    panel.setResults(classes, methods, imports, duplicates);
  }
  /**
   * Set the status message
   */
  public void setStatus(String status) {
    panel.setStatus(status);
  }

  /**
   * Get the underlying panel (for testing)
   */
  public UnusedCodeToolWindowPanel getPanel() {
    return panel;
  }

  /**
   * Check if the tool window is visible
   */
  public boolean isVisible() {
    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
    ToolWindow toolWindow = toolWindowManager.getToolWindow("Unused Code Detector");
    return toolWindow != null && toolWindow.isVisible();
  }

  /**
   * Force refresh of the tool window content
   */
  public void refresh() {
    panel.repaint();
  }

  /**
   * Dispose the tool window (called when project closes)
   */
  public void dispose() {
    // Clean up resources
    panel.clearResults();
    project.putUserData(KEY, null);
  }
}
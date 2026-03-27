package org.cakk.memoryleak.settings;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
        name = "MemoryLeakSettings",
        storages = @Storage("memoryLeakDetector.xml")
)
public final class MemoryLeakSettings implements PersistentStateComponent<MemoryLeakSettings> {

  // Detection thresholds
  private double rapidGrowthThreshold = 10.0;      // 10% growth per interval
  private double warningGrowthThreshold = 20.0;    // 20% growth from baseline
  private double highRiskGrowthThreshold = 50.0;   // 50% growth from baseline
  private double lowGcEfficiencyThreshold = 20.0;  // 20% GC efficiency
  private double criticalGcEfficiencyThreshold = 10.0; // 10% GC efficiency

  // Monitoring settings
  private boolean autoStartMonitoring = false;
  private int monitoringIntervalSeconds = 5;
  private int baselineSettleTimeSeconds = 2;
  private int maxGcEventsToKeep = 100;
  private int maxSnapshotsToKeep = 1000;

  // Alert settings
  private boolean showGcConfirmationDialog = true;
  private boolean showLeakNotifications = true;
  private boolean autoRunGcOnHighRisk = false;
  private boolean logToConsole = true;

  // UI settings
  private boolean showToolWindowOnStartup = true;
  private boolean showMemoryChart = true;
  private boolean showGcEvents = true;

  // Advanced settings
  private boolean enableLeakSimulation = true;
  private boolean trackAllocationStacks = false;
  private boolean enableAutoDumpOnLeak = false;
  private String dumpPath = "";

  public static MemoryLeakSettings getInstance(Project project) {
    return project.getService(MemoryLeakSettings.class);
  }

  @Override
  public @Nullable MemoryLeakSettings getState() {
    return this;
  }

  @Override
  public void loadState(@NotNull MemoryLeakSettings state) {
    XmlSerializerUtil.copyBean(state, this);
  }

  // Getters and Setters

  public double getRapidGrowthThreshold() {
    return rapidGrowthThreshold;
  }

  public void setRapidGrowthThreshold(double rapidGrowthThreshold) {
    this.rapidGrowthThreshold = rapidGrowthThreshold;
  }

  public double getWarningGrowthThreshold() {
    return warningGrowthThreshold;
  }

  public void setWarningGrowthThreshold(double warningGrowthThreshold) {
    this.warningGrowthThreshold = warningGrowthThreshold;
  }

  public double getHighRiskGrowthThreshold() {
    return highRiskGrowthThreshold;
  }

  public void setHighRiskGrowthThreshold(double highRiskGrowthThreshold) {
    this.highRiskGrowthThreshold = highRiskGrowthThreshold;
  }

  public double getLowGcEfficiencyThreshold() {
    return lowGcEfficiencyThreshold;
  }

  public void setLowGcEfficiencyThreshold(double lowGcEfficiencyThreshold) {
    this.lowGcEfficiencyThreshold = lowGcEfficiencyThreshold;
  }

  public double getCriticalGcEfficiencyThreshold() {
    return criticalGcEfficiencyThreshold;
  }

  public void setCriticalGcEfficiencyThreshold(double criticalGcEfficiencyThreshold) {
    this.criticalGcEfficiencyThreshold = criticalGcEfficiencyThreshold;
  }

  public boolean isAutoStartMonitoring() {
    return autoStartMonitoring;
  }

  public void setAutoStartMonitoring(boolean autoStartMonitoring) {
    this.autoStartMonitoring = autoStartMonitoring;
  }

  public int getMonitoringIntervalSeconds() {
    return monitoringIntervalSeconds;
  }

  public void setMonitoringIntervalSeconds(int monitoringIntervalSeconds) {
    this.monitoringIntervalSeconds = monitoringIntervalSeconds;
  }

  public int getBaselineSettleTimeSeconds() {
    return baselineSettleTimeSeconds;
  }

  public void setBaselineSettleTimeSeconds(int baselineSettleTimeSeconds) {
    this.baselineSettleTimeSeconds = baselineSettleTimeSeconds;
  }

  public boolean isShowGcConfirmationDialog() {
    return showGcConfirmationDialog;
  }

  public void setShowGcConfirmationDialog(boolean showGcConfirmationDialog) {
    this.showGcConfirmationDialog = showGcConfirmationDialog;
  }

  public boolean isShowLeakNotifications() {
    return showLeakNotifications;
  }

  public void setShowLeakNotifications(boolean showLeakNotifications) {
    this.showLeakNotifications = showLeakNotifications;
  }

  public boolean isAutoRunGcOnHighRisk() {
    return autoRunGcOnHighRisk;
  }

  public void setAutoRunGcOnHighRisk(boolean autoRunGcOnHighRisk) {
    this.autoRunGcOnHighRisk = autoRunGcOnHighRisk;
  }

  public boolean isLogToConsole() {
    return logToConsole;
  }

  public void setLogToConsole(boolean logToConsole) {
    this.logToConsole = logToConsole;
  }

  public boolean isShowToolWindowOnStartup() {
    return showToolWindowOnStartup;
  }

  public void setShowToolWindowOnStartup(boolean showToolWindowOnStartup) {
    this.showToolWindowOnStartup = showToolWindowOnStartup;
  }

  public boolean isShowMemoryChart() {
    return this.showMemoryChart;
  }

  public void setShowMemoryChart(boolean showMemoryChart) {
    this.showMemoryChart = showMemoryChart;
  }
}
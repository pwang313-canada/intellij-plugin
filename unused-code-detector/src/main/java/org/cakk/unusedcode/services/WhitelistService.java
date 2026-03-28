package org.cakk.unusedcode.services;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;
import java.util.HashSet;
import java.util.Set;

@State(
        name = "UnusedCodeWhitelist",
        storages = @Storage("$APP_CONFIG$\\unused-code-whitelist.xml")
)
public final class WhitelistService implements PersistentStateComponent<WhitelistService.State> {

  private State state = new State();

  public static class State {
    public Set<String> classes = new HashSet<>();
    public Set<String> methods = new HashSet<>();
  }

  public static WhitelistService getInstance() {
    return ServiceManager.getService(WhitelistService.class);
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    this.state = state;
  }

  public boolean isClassWhitelisted(String className) {
    return state.classes.contains(className);
  }

  public boolean isMethodWhitelisted(String className, String methodName) {
    return state.methods.contains(className + "#" + methodName);
  }

  public void addClassToWhitelist(String className) {
    state.classes.add(className);
    logWhitelistPath();
  }

  public void addMethodToWhitelist(String className, String methodName) {
    state.methods.add(className + "#" + methodName);
    logWhitelistPath();
  }

  public void removeClassFromWhitelist(String className) {
    state.classes.remove(className);
  }

  public void removeMethodFromWhitelist(String className, String methodName) {
    state.methods.remove(className + "#" + methodName);
  }

  private void logWhitelistPath() {
    String configDir = String.valueOf(PathManager.getConfigDir());
    String filePath = configDir + "\\unused-code-whitelist.xml";
    System.out.println("Whitelist file will be saved to: " + filePath);
    // Optionally, also print when the file actually exists (after a save)
    // But the file is written asynchronously; this gives the intended location.
  }
}
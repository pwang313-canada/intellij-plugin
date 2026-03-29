package org.cakk.unusedcode.services;

import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@State(
        name = "UnusedCodeWhitelist",
        storages = @Storage("$APP_CONFIG$/unused-code-whitelist.xml")
)
public final class WhitelistService implements PersistentStateComponent<WhitelistService.State> {

  // In-memory thread-safe sets
  private final Set<String> classes = ConcurrentHashMap.newKeySet();
  private final Set<String> methods = ConcurrentHashMap.newKeySet();

  public static class State {
    public Set<String> classes = new HashSet<>();
    public Set<String> methods = new HashSet<>();
  }

  public static WhitelistService getInstance() {
    return ServiceManager.getService(WhitelistService.class);
  }

  @Override
  public State getState() {
    State state = new State();
    state.classes.addAll(classes);
    state.methods.addAll(methods);
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    classes.clear();
    classes.addAll(state.classes);
    methods.clear();
    methods.addAll(state.methods);
  }

  public boolean isClassWhitelisted(String className) {
    return classes.contains(className);
  }

  public boolean isMethodWhitelisted(String className, String methodName) {
    return methods.contains(className + "#" + methodName);
  }

  public void addClassToWhitelist(String className) {
    classes.add(className);
  }

  public void addMethodToWhitelist(String className, String methodName) {
    methods.add(className + "#" + methodName);
  }

  public void removeClassFromWhitelist(String className) {
    classes.remove(className);
  }

  public void removeMethodFromWhitelist(String className, String methodName) {
    methods.remove(className + "#" + methodName);
  }
}
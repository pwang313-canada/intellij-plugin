// src/main/java/org/cakk/memoryleak/listeners/ApplicationCloseListener.java
package org.cakk.memoryleak.listeners;

import com.intellij.ide.AppLifecycleListener;

public class ApplicationCloseListener implements AppLifecycleListener {

  @Override
  public void appWillBeClosed(boolean isRestart) {
    // Clean up any global resources if needed
  }
}
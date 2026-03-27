package org.cakk.memoryleak.app;

import java.util.ArrayList;
import java.util.List;

import java.lang.ref.WeakReference;

public class StaticReferenceFix {

  // Use WeakReference so entries can be garbage collected
  private static final List<WeakReference<byte[]>> cache = new ArrayList<>();

  public static void main(String[] args) {
    // Add a cleanup thread to remove null references
    Thread cleanupThread = new Thread(() -> {
      while (true) {
        try {
          Thread.sleep(5000); // Clean every 5 seconds
          cleanupNullReferences();
        } catch (InterruptedException e) {
          break;
        }
      }
    });
    cleanupThread.setDaemon(true);
    cleanupThread.start();

    while (true) {
      byte[] data = new byte[1024 * 1024];
      cache.add(new WeakReference<>(data));

      System.out.println("Added 1MB, cache size: " + cache.size() +
              ", active: " + countActiveReferences());

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  private static void cleanupNullReferences() {
    int beforeSize = cache.size();
    cache.removeIf(ref -> ref.get() == null);
    int removed = beforeSize - cache.size();
    if (removed > 0) {
      System.out.println("Cleaned up " + removed + " expired references");
    }
  }

  private static int countActiveReferences() {
    int count = 0;
    for (WeakReference<byte[]> ref : cache) {
      if (ref.get() != null) {
        count++;
      }
    }
    return count;
  }
}
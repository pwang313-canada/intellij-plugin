package org.cakk.memoryleak.app;

import java.util.ArrayList;
import java.util.List;

public class StaticReference {

  // Static list → lives for entire app lifecycle
  private static final List<byte[]> cache = new ArrayList<>();

  public static void main(String[] args) {

    while (true) {
      // Allocate 1MB each loop
      byte[] data = new byte[1024 * 1024];
      cache.add(data);

      System.out.println("Added 1MB, cache size: " + cache.size());

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  // code fix for memory leak
  // from VisualVM, after you press "Perform GC", if you can see the "Heap Size" and "Used Heap" drop, it means code is fine
  /*
    private static final List<WeakReference<byte[]>> cache = new ArrayList<>();
    private static void cleanupNullReferences() {
        Iterator<WeakReference<byte[]>> iterator = cache.iterator();
        while (iterator.hasNext()) {
            WeakReference<byte[]> ref = iterator.next();
            if (ref.get() == null) {
                iterator.remove();
            }
        }
    }
    public static void main(String[] args) {
    while (true) {
      byte[] data = new byte[1024 * 1024];
      cache.add(new WeakReference<>(data));

      // Clean up nullified references periodically
      cleanupNullReferences();

      System.out.println("Added 1MB, cache size: " + cache.size());

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

   */
}
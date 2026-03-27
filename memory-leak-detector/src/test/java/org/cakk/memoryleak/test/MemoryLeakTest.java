package org.cakk.memoryleak.test;


import java.util.ArrayList;
import java.util.List;

public class MemoryLeakTest {

  private static final List<byte[]> LEAK_LIST = new ArrayList<>();

  public static void main(String[] args) throws InterruptedException {
    System.out.println("Starting memory leak test...");

    for (int i = 0; i < 100; i++) {
      // This is the leak - adding to static list prevents GC
      LEAK_LIST.add(new byte[1024 * 1024]); // 1 MB each

      System.out.println("Added leak #" + (i + 1) +
              ", total: " + (LEAK_LIST.size()) + " MB");

      Thread.sleep(1000);
    }

    System.out.println("Test complete. Memory should be leaked.");
  }
}
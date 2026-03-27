package org.cakk.memoryleak.test;

import java.util.ArrayList;
import java.util.List;

public class LeakTest {

  private static final List<byte[]> leakList = new ArrayList<>();

  public static void main(String[] args) throws InterruptedException {
    // Simulate memory leak
    for (int i = 0; i < 100; i++) {
      // This creates a memory leak
      leakList.add(new byte[1024 * 1024]); // 1 MB leak each iteration

      System.out.println("Added leak #" + (i + 1) +
              ", total leaks: " + leakList.size() + " MB");

      Thread.sleep(500);
    }
  }
}
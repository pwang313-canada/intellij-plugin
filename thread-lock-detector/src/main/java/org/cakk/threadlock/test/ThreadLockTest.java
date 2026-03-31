package org.cakk.threadlock.test;

public class ThreadLockTest {

  private final Object lock = new Object();

  // Synchronized method (issue)
  public synchronized void synchronizedMethod() {
    System.out.println("Inside synchronized method");
  }

  // Synchronized on 'this' (issue)
  public void synchronizedThisBlock() {
    synchronized (this) {
      System.out.println("Synchronizing on 'this'");
    }
  }

  // Synchronized on string literal (issue)
  public void synchronizedStringBlock() {
    synchronized ("LOCK") {
      System.out.println("Synchronizing on string literal");
    }
  }

  // Synchronized on Class object (issue)
  public void synchronizedClassBlock() {
    synchronized (ThreadLockTest.class) {
      System.out.println("Synchronizing on Class object");
    }
  }

  // Safe synchronized block
  public void safeBlock() {
    synchronized (lock) {
      System.out.println("Using private final lock object");
    }
  }

  public static void main(String[] args) {
    ThreadLockTest test = new ThreadLockTest();

    test.synchronizedMethod();
    test.synchronizedThisBlock();
    test.synchronizedStringBlock();
    test.synchronizedClassBlock();
    test.safeBlock();
  }
}
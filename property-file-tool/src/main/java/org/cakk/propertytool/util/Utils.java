package org.cakk.propertytool.util;

import com.intellij.openapi.vfs.VirtualFile;

public class Utils {
  public static boolean isPropertiesFile(VirtualFile file) {
    String name = file.getName();
    return name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml");
  }

  public static boolean isPropertyFile(VirtualFile file) {
    String name = file.getName();
    return name.endsWith(".properties");
  }

  public static boolean isYmlFile(VirtualFile file) {
    String name = file.getName();
    return name.endsWith(".yml") || name.endsWith(".yaml");
  }
}

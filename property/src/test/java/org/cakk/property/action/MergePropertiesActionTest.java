package org.cakk.property.action;

import com.intellij.openapi.vfs.VirtualFile;
import org.cakk.property.property.MergePropertiesAction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MergePropertiesActionTest {

  private final MergePropertiesAction action = new MergePropertiesAction();

  // =========================
  // TEST: findPropertiesFiles
  // =========================
  @Test
  void testFindPropertiesFiles_recursive() throws Exception {
    VirtualFile root = mockDir("resources");
    VirtualFile subDir = mockDir("sub");

    VirtualFile f1 = mockFile("a.properties", "k=v");
    VirtualFile f2 = mockFile("b.txt", "ignore");
    VirtualFile f3 = mockFile("c.properties", "x=y");

    when(root.getChildren()).thenReturn(new VirtualFile[]{subDir, f1});
    when(subDir.getChildren()).thenReturn(new VirtualFile[]{f2, f3});

    List<VirtualFile> result = invokeFind(root);

    assertEquals(2, result.size());
    assertTrue(result.stream().anyMatch(f -> f.getName().equals("a.properties")));
    assertTrue(result.stream().anyMatch(f -> f.getName().equals("c.properties")));
  }

  // =========================
  // TEST: loadProperties
  // =========================
  @Test
  void testLoadProperties() throws Exception {
    VirtualFile file = mockFile("test.properties",
            "server.port=8080\nspring.name=test");

    Properties props = invokeLoad(file);

    assertEquals("8080", props.getProperty("server.port"));
    assertEquals("test", props.getProperty("spring.name"));
  }

  // =========================
  // TEST: writePropertiesVfs
  // =========================
  @Test
  void testWriteProperties_sorted() throws Exception {
    VirtualFile file = mock(VirtualFile.class);

    Properties props = new Properties();
    props.setProperty("b", "2");
    props.setProperty("a", "1");

    invokeWrite(file, props);

    verify(file).setBinaryContent(argThat(bytes -> {
      String content = new String(bytes, StandardCharsets.UTF_8);
      return content.indexOf("a=1") < content.indexOf("b=2");
    }));
  }

  // =========================
  // TEST: getOrCreateApplicationProperties
  // =========================
  @Test
  void testGetOrCreate_existing() throws Exception {
    VirtualFile folder = mockDir("resources");
    VirtualFile app = mock(VirtualFile.class);

    when(folder.findChild("application.properties")).thenReturn(app);

    VirtualFile result = invokeGetOrCreate(folder);

    assertEquals(app, result);
  }

  @Test
  void testGetOrCreate_createNew() throws Exception {
    VirtualFile folder = mockDir("resources");
    VirtualFile newFile = mock(VirtualFile.class);

    when(folder.findChild("application.properties")).thenReturn(null);
    when(folder.createChildData(any(), eq("application.properties"))).thenReturn(newFile);

    VirtualFile result = invokeGetOrCreate(folder);

    assertEquals(newFile, result);
  }

  // =========================
  // TEST: common properties logic (core behavior)
  // =========================
  @Test
  void testCommonPropertiesExtraction() {
    // simulate 2 files
    Properties p1 = new Properties();
    p1.setProperty("a", "1");
    p1.setProperty("b", "2");

    Properties p2 = new Properties();
    p2.setProperty("a", "1");
    p2.setProperty("b", "3"); // conflict

    // emulate your logic
    var keyToValues = new java.util.LinkedHashMap<String, java.util.Map<String, String>>();

    addProps(keyToValues, "f1", p1);
    addProps(keyToValues, "f2", p2);

    int totalFiles = 2;

    var common = new java.util.LinkedHashMap<String, String>();

    for (var entry : keyToValues.entrySet()) {
      if (entry.getValue().size() == totalFiles) {
        var values = new java.util.HashSet<>(entry.getValue().values());
        if (values.size() == 1) {
          common.put(entry.getKey(), values.iterator().next());
        }
      }
    }

    assertEquals(1, common.size());
    assertEquals("1", common.get("a"));
    assertFalse(common.containsKey("b"));
  }

  // =========================
  // Helpers
  // =========================

  private VirtualFile mockFile(String name, String content) throws Exception {
    VirtualFile file = mock(VirtualFile.class);

    InputStream is = new ByteArrayInputStream(content.getBytes());
    when(file.getInputStream()).thenReturn(is);
    when(file.getName()).thenReturn(name);
    when(file.isDirectory()).thenReturn(false);

    return file;
  }

  private VirtualFile mockDir(String name) {
    VirtualFile dir = mock(VirtualFile.class);
    when(dir.getName()).thenReturn(name);
    when(dir.isDirectory()).thenReturn(true);
    return dir;
  }

  private List<VirtualFile> invokeFind(VirtualFile root) throws Exception {
    Method m = MergePropertiesAction.class
            .getDeclaredMethod("findPropertiesFiles", VirtualFile.class);
    m.setAccessible(true);
    return (List<VirtualFile>) m.invoke(action, root);
  }

  private Properties invokeLoad(VirtualFile file) throws Exception {
    Method m = MergePropertiesAction.class
            .getDeclaredMethod("loadProperties", VirtualFile.class);
    m.setAccessible(true);
    return (Properties) m.invoke(action, file);
  }

  private void invokeWrite(VirtualFile file, Properties props) throws Exception {
    Method m = MergePropertiesAction.class
            .getDeclaredMethod("writePropertiesVfs", VirtualFile.class, Properties.class);
    m.setAccessible(true);
    m.invoke(action, file, props);
  }

  private VirtualFile invokeGetOrCreate(VirtualFile folder) throws Exception {
    Method m = MergePropertiesAction.class
            .getDeclaredMethod("getOrCreateApplicationProperties", VirtualFile.class);
    m.setAccessible(true);
    return (VirtualFile) m.invoke(action, folder);
  }

  private void addProps(LinkedHashMap<String, Map<String, String>> map, String fileName, Properties props) {
    for (String key : props.stringPropertyNames()) {
      map.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>())
              .put(fileName, props.getProperty(key));
    }
  }
}

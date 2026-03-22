package org.cakk.property.action;

import com.intellij.openapi.vfs.VirtualFile;
import org.cakk.property.property.ConvertPropertiesToYamlAction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

import static org.mockito.Mockito.*;

class ConvertPropertiesToYamlActionTest {

  private final ConvertPropertiesToYamlAction action = new ConvertPropertiesToYamlAction();

  // ========================
  // TEST: insert() logic
  // ========================
  @Test
  void testInsertSimpleKey() {
    Map<String, Object> map = new LinkedHashMap<>();

    invokeInsert(map, "server.port", "8080");

    assertEquals("8080", ((Map<?, ?>) map.get("server")).get("port"));
  }

  @Test
  void testInsertNestedKey() {
    Map<String, Object> map = new LinkedHashMap<>();

    invokeInsert(map, "spring.datasource.url", "jdbc:mysql");

    Map<?, ?> spring = (Map<?, ?>) map.get("spring");
    Map<?, ?> datasource = (Map<?, ?>) spring.get("datasource");

    assertEquals("jdbc:mysql", datasource.get("url"));
  }

  @Test
  void testInsertListIndex() {
    Map<String, Object> map = new LinkedHashMap<>();

    invokeInsert(map, "servers[0].host", "localhost");
    invokeInsert(map, "servers[0].port", "8080");

    List<?> servers = (List<?>) map.get("servers");
    Map<?, ?> first = (Map<?, ?>) servers.get(0);

    assertEquals("localhost", first.get("host"));
    assertEquals("8080", first.get("port"));
  }

  @Test
  void testInsertListMultipleIndexes() {
    Map<String, Object> map = new LinkedHashMap<>();

    invokeInsert(map, "servers[0].host", "a");
    invokeInsert(map, "servers[1].host", "b");

    List<?> servers = (List<?>) map.get("servers");

    assertEquals("a", ((Map<?, ?>) servers.get(0)).get("host"));
    assertEquals("b", ((Map<?, ?>) servers.get(1)).get("host"));
  }

  // ========================
  // TEST: YAML generation
  // ========================
  @Test
  void testGenerateYaml() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("server", Map.of("port", "8080"));

    String yaml = action.generateYaml(map);

    assertTrue(yaml.contains("server"));
    assertTrue(yaml.contains("port"));
    assertTrue(yaml.contains("8080"));
  }

  // ========================
  // TEST: loadProperties()
  // ========================
  @Test
  void testLoadProperties() throws Exception {
    VirtualFile file = mock(VirtualFile.class);

    String content = "server.port=8080\nspring.name=test";

    InputStream is = new ByteArrayInputStream(content.getBytes());

    when(file.getInputStream()).thenReturn(is);

    Map<String, Object> result = action.loadProperties(file);

    Map<?, ?> server = (Map<?, ?>) result.get("server");
    assertEquals("8080", server.get("port"));

    Map<?, ?> spring = (Map<?, ?>) result.get("spring");
    assertEquals("test", spring.get("name"));
  }

  // ========================
  // TEST: collectPropertiesFiles()
  // ========================
  @Test
  void testCollectPropertiesFiles() {
    VirtualFile file1 = mockFile("a.properties", false);
    VirtualFile file2 = mockFile("b.txt", false);
    VirtualFile dir = mockFile("dir", true);

    when(dir.getChildren()).thenReturn(new VirtualFile[]{file1, file2});

    List<VirtualFile> result = action.collectPropertiesFiles(new VirtualFile[]{dir});

    assertEquals(1, result.size());
    assertEquals("a.properties", result.get(0).getName());
  }

  // ========================
  // Helpers
  // ========================

  private VirtualFile mockFile(String name, boolean isDir) {
    VirtualFile file = mock(VirtualFile.class);
    when(file.getName()).thenReturn(name);
    when(file.isDirectory()).thenReturn(isDir);
    return file;
  }

  // Use reflection because insert() is private static
  private void invokeInsert(Map<String, Object> map, String key, String value) {
    try {
      var method = ConvertPropertiesToYamlAction.class
              .getDeclaredMethod("insert", Map.class, String.class, String.class);
      method.setAccessible(true);
      method.invoke(null, map, key, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
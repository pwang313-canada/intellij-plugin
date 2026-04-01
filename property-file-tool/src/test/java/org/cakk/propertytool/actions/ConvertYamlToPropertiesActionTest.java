package org.cakk.propertytool.actions;

import com.intellij.openapi.vfs.VirtualFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConvertYamlToPropertiesActionTest {

  private final ConvertYamlToPropertiesAction action = new ConvertYamlToPropertiesAction();

  // =========================
  // TEST: convertYaml (core)
  // =========================
  @Test
  void testConvertSimpleYaml() throws Exception {
    VirtualFile file = mockYamlFile("test.yml",
            "server:\n  port: 8080\n");

    String result = invokeConvertYaml(file);

    assertTrue(result.contains("server.port=8080"));
  }

  @Test
  void testConvertNestedYaml() throws Exception {
    VirtualFile file = mockYamlFile("test.yml",
            "spring:\n  datasource:\n    url: jdbc:mysql\n");

    String result = invokeConvertYaml(file);

    assertTrue(result.contains("spring.datasource.url=jdbc:mysql"));
  }

  @Test
  void testConvertYamlWithList() throws Exception {
    VirtualFile file = mockYamlFile("test.yml",
            "servers:\n" +
                    "  - host: localhost\n" +
                    "    port: 8080\n" +
                    "  - host: remote\n" +
                    "    port: 9090\n");

    String result = invokeConvertYaml(file);

    assertTrue(result.contains("servers[0].host=localhost"));
    assertTrue(result.contains("servers[1].port=9090"));
  }

  @Test
  void testConvertYamlPrimitiveRoot() throws Exception {
    VirtualFile file = mockYamlFile("test.yml", "hello");

    String result = invokeConvertYaml(file);

    assertTrue(result.contains("root=hello"));
  }

  @Test
  void testConvertEmptyYamlThrowsException() throws Exception {
    // Create a VirtualFile that returns an empty InputStream
    VirtualFile file = mock(VirtualFile.class);
    when(file.getName()).thenReturn("test.yml");
    when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));

// Act & Assert
    InvocationTargetException exception = assertThrows(
            InvocationTargetException.class,
            () -> invokeConvertYaml(file)
    );

    // Assert the root cause is the expected exception type
    Throwable cause = exception.getCause();
    assertNotNull(cause, "Expected a cause in InvocationTargetException");
    assertInstanceOf(IOException.class, cause,
            "Expected IOException for empty YAML file");

    // Optional: You can still verify the message if you want (recommended for better test clarity)
    assertEquals("Empty YAML file", cause.getMessage());
  }

  // =========================
  // TEST: flattenYamlStructure
  // =========================
  @Test
  void testFlattenMap() throws Exception {
    Map<String, Object> yaml = new LinkedHashMap<>();
    yaml.put("server", Map.of("port", 8080));

    Properties props = new Properties();

    invokeFlatten("", yaml, props);

    assertEquals("8080", props.getProperty("server.port"));
  }

  @Test
  void testFlattenList() throws Exception {
    Map<String, Object> yaml = new LinkedHashMap<>();
    yaml.put("items", List.of("a", "b"));

    Properties props = new Properties();

    invokeFlatten("", yaml, props);

    assertEquals("a", props.getProperty("items[0]"));
    assertEquals("b", props.getProperty("items[1]"));
  }

  // =========================
  // TEST: buildPropertiesContent
  // =========================
  @Test
  void testBuildPropertiesContentSorted() throws Exception {
    Properties props = new Properties();
    props.setProperty("b", "2");
    props.setProperty("a", "1");

    VirtualFile file = mock(VirtualFile.class);
    when(file.getName()).thenReturn("test.yml");

    String content = invokeBuildContent(file, props);

    int indexA = content.indexOf("a=1");
    int indexB = content.indexOf("b=2");

    assertTrue(indexA < indexB); // sorted
  }

  // =========================
  // Helpers
  // =========================

  private VirtualFile mockYamlFile(String name, String content) throws Exception {
    VirtualFile file = mock(VirtualFile.class);

    InputStream is = new ByteArrayInputStream(content.getBytes());

    when(file.getInputStream()).thenReturn(is);
    when(file.getName()).thenReturn(name);

    return file;
  }

  private String invokeConvertYaml(VirtualFile file) throws Exception {
    Method method = ConvertYamlToPropertiesAction.class
            .getDeclaredMethod("convertYaml", VirtualFile.class);
    method.setAccessible(true);
    return (String) method.invoke(action, file);
  }

  private void invokeFlatten(String prefix, Map<String, Object> map, Properties props) throws Exception {
    Method method = ConvertYamlToPropertiesAction.class
            .getDeclaredMethod("flattenYamlStructure", String.class, Map.class, Properties.class);
    method.setAccessible(true);
    method.invoke(action, prefix, map, props);
  }

  private String invokeBuildContent(VirtualFile file, Properties props) throws Exception {
    Method method = ConvertYamlToPropertiesAction.class
            .getDeclaredMethod("buildPropertiesContent", VirtualFile.class, Properties.class);
    method.setAccessible(true);
    return (String) method.invoke(action, file, props);
  }
}

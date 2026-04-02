plugins {
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "org.cakk.memory-leak-detector"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

intellij {
    version.set("2023.3")
    type.set("IC") // IntelliJ IDEA Community Edition
    plugins.set(listOf("com.intellij.java"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("253.*")

        version.set(project.version.toString())
        pluginDescription.set("""
    <h2>Memory Leak Detector</h2>
    <p>Real-time memory leak detection for IntelliJ IDEA that helps you identify and prevent memory issues in your Java applications.</p>

    <h3>📊 Features</h3>
    <ul>
        <li><strong>Real-time Memory Monitoring</strong> - Live visualization of heap, young generation, and old generation memory usage</li>
        <li><strong>Intelligent Leak Detection</strong> - Automatically detects rapid memory growth patterns and potential memory leaks</li>
        <li><strong>Multi-metric Visualization</strong> - Toggle between heap, young gen, and old gen views with color-coded charts</li>
        <li><strong>GC Event Analysis</strong> - Tracks garbage collection efficiency and frequency</li>
        <li><strong>Smart Alerts</strong> - Prompts user when rapid old generation growth is detected</li>
        <li><strong>Remote Monitoring</strong> - Monitor memory usage of remote Java applications via JMX</li>
        <li><strong>Manual GC Control</strong> - Optional System.gc() invocation with user confirmation</li>
        <li><strong>Historical Data</strong> - Maintains up to 200 data points for trend analysis</li>
    </ul>
    
    <h3>🚀 Usage</h3>
    <ol>
        <li><strong>Start Monitoring</strong>
            <ul>
                <li>To monitor a remote Java application, add these VM options:</li>
                <li><code>-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false</code></li>
                <li>Trigger the tool window by clicking the left‑bottom <strong>Memory Monitor</strong> button.</li>
                <li>Monitor the heap size change by pressing <code>Run GC</code>. If the heap size didn't decreases as expected, …</li>
                <li>Refer to <a href="https://github.com/pwang313-canada/CommonJavaIssueDetect/tree/main/MemoryLeakExample">this example project</a> for a memory leak check.</li>
            </ul>
        </li>
        <li><strong>View Memory Charts</strong>
            <ul>
                <li>Monitor real-time memory usage in the tool window</li>
                <li>Toggle individual metrics (Heap/Old Gen/Young Gen) using checkboxes</li>
                <li>Hover over the chart to see exact values at specific timestamps</li>
            </ul>
        </li>
        <li><strong>Leak Detection Alerts</strong>
            <ul>
                <li>The detector will automatically notify you when suspicious memory patterns are detected</li>
                <li>When rapid old gen growth is detected, you'll receive a prompt with options to investigate or invoke GC</li>
            </ul>
        </li>
        <li><strong>Analyze Results</strong>
            <ul>
                <li>Review GC efficiency metrics in the statistics panel</li>
                <li>Export memory usage data for further analysis (coming soon)</li>
            </ul>
        </li>
    </ol>
    
    <h3>💡 Tips</h3>
    <ul>
        <li>Use the checkbox toggles to focus on specific memory areas when debugging</li>
        <li>Remote monitoring requires JMX to be enabled on the target application with <code>-Dcom.sun.management.jmxremote</code></li>
        <li>The detector works best when monitoring applications for at least 30 seconds to establish baseline patterns</li>
    </ul>
    <p>Supported IntelliJ versions: 2023.3 and later.</p>

""".trimIndent())

        changeNotes.set("""
            <h3>Version 1.0.1</h3>
            <ul>
                <li>🎉 Refer a git repo to check suspicious memory leak application</li>
            </ul>
        """.trimIndent())
    }

    runIde {
        jvmArgs("-Xmx2g")
        // Add required opens for Java modules
        jvmArgs("--add-opens=java.desktop/javax.swing=ALL-UNNAMED")
        jvmArgs("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        jvmArgs("--add-opens=java.base/java.util=ALL-UNNAMED")
        jvmArgs("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
        jvmArgs("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED")    }

    compileJava {
        options.encoding = "UTF-8"
        // Disable module warnings
        options.compilerArgs.add("-Xlint:-module")
        // Add the desktop module
        options.compilerArgs.add("--add-modules=java.desktop")
    }

    clean {
        delete("src/main/java/module-info.java")
    }
}

tasks.jar {
    exclude("module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Add runtime arguments for the plugin itself
tasks.withType<JavaExec> {
    jvmArgs("--add-opens=java.desktop/javax.swing=ALL-UNNAMED")
    jvmArgs("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
}
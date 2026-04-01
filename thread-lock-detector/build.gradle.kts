plugins {
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "org.cakk.threadlock-detector"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("org.mockito:mockito-core:5.7.0")
}

intellij {
    version.set("2024.3")        // match the IDE you run
    type.set("IC")               // IntelliJ Community Edition
    plugins.set(listOf("java"))  // only Java plugin dependency
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("263.*")          // maximum 2026.x (263.*)

        version.set(project.version.toString())
        pluginDescription.set("""
    <h2>Thread Lock Checker</h2>
    <p>Detects thread safety issues in Java code – both at compile time and during runtime execution.</p>

    <h3>Features</h3>
    <ul>
        <li><strong>Static analysis</strong> – scans Java files for common anti‑patterns:
            <ul>
                <li><strong>Synchronized methods</strong> – may hold locks longer than necessary, potentially reducing concurrency.</li>
                <li><strong>Synchronizing on 'this'</strong> – discouraged because it exposes the lock to external code.</li>
                <li><strong>Synchronizing on String literals</strong> – can cause global contention across unrelated parts of the application.</li>
                <li><strong>Synchronizing on Class objects</strong> – often not recommended and may lead to unintended lock scope.</li>
            </ul>
            Right‑click any Java file or source folder, then choose <strong>Analyze → Analyze Static Thread Lock</strong>. Results are shown in the <strong>Thread Lock Checker</strong> tool window.
        </li>
        <li><strong>Runtime deadlock monitor</strong> – attaches to a running Java process and detects actual deadlocks.
            <ul>
                <li>Open the tool window (bottom left) and click <strong>Refresh</strong> to list all running Java applications.</li>
                <li>Select a process from the dropdown and click <strong>Connect</strong>.</li>
                <li>Once connected, click <strong>Start Monitoring</strong>. Any deadlock is instantly reported with full stack traces.</li>
            </ul>
        </li>
    </ul>

    <h3>User Interface</h3>
    <ul>
        <li>The tool window is divided into two resizable sections:
            <ul>
                <li><strong>Top</strong> – static analysis results in a table (double‑click any row to navigate to the source line).</li>
                <li><strong>Bottom</strong> – runtime monitor with collapsible connection controls, a clear log button, and a scrollable deadlock log.</li>
            </ul>
        </li>
        <li>Static issues are highlighted with severity icons: <span style="color:red">🔴</span> for errors, <span style="color:orange">🟠</span> for warnings, and <span style="color:blue">ℹ️</span> for information.</li>
        <li>Runtime monitor includes a <strong>Clear Log</strong> button to reset the deadlock display.</li>
    </ul>

    <h3>Requirements</h3>
    <p>IntelliJ IDEA 2023.3 or later (Community or Ultimate). Java 11+.</p>

    <p><strong>Note</strong>: The runtime monitor requires that the target Java process runs under the same user account as IntelliJ.</p>
        """.trimIndent())

        changeNotes.set("""
            <h3>Version 1.0.0</h3>
            <ul>
                <li>🎉 Initial release</li>
            </ul>
        """.trimIndent())
    }

    // Optional: avoid buildSearchableOptions crash during first build
    buildSearchableOptions {
        enabled = false
    }
}
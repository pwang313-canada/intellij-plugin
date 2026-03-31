plugins {
    id("org.jetbrains.intellij") version "1.17.3"
    // Optional: add Kotlin if your plugin uses Kotlin
    // kotlin("jvm") version "1.9.10"
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
    version.set("2023.3")        // match the IDE you run
    type.set("IC")               // IntelliJ Community Edition
    plugins.set(listOf("java"))  // only Java plugin dependency
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("263.*")          // maximum 2026.x (263.*)

        version.set(project.version.toString())
        pluginDescription.set("""
            <h2>Thread Lock Detector for Java</h2>
            <p>This plugin helps you identify potential thread safety issues in Java code by detecting common anti-patterns related to synchronization and locking.</p>
            
            <h3>Detected Issues</h3>
            <ul>
                <li><strong>Synchronized methods</strong> – may hold locks longer than necessary, potentially reducing concurrency.</li>
                <li><strong>Synchronizing on 'this'</strong> – discouraged because it exposes the lock to external code.</li>
                <li><strong>Synchronizing on String literals</strong> – can cause global contention across unrelated parts of the application.</li>
                <li><strong>Synchronizing on Class objects</strong> – often not recommended and may lead to unintended lock scope.</li>
            </ul>
            
            <h3>How to Use</h3>
            <ul>
                <li>Right‑click a Java file or source folder in the project view, then select <strong>Analyze → Analyze Thread Lock</strong>.</li>
                <li>Alternatively, from the edit window, open the java file, right click, go to <strong>Analyze → Analyze Thread Lock</strong> while a Java file is open or selected.</li>
                <li>Results are displayed in the <strong>Thread Lock Checker</strong> tool window.</li>
            </ul>
            
            <p>The analysis runs in the background and shows detailed warnings with line numbers and suggested fixes.</p>
            
            <p>Supported IntelliJ versions: 2023.3 and later.</p>
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
plugins {
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "org.cakk.property-file-tool"
version = "1.0.1"

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
    version.set("2024.3")
    type.set("IC") // IntelliJ IDEA Community Edition
    plugins.set(listOf("com.intellij.java"))
}

// ✅ Fix 1: Use a specific Java toolchain to avoid Java 25 issues
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21)) // Use JDK 21 (stable & supported)
    }
}

tasks {
    patchPluginXml {
        // ✅ Fix 2: Update since‑build to match the target IDE version (2024.3 = build 243)
        sinceBuild.set("243")
        untilBuild.set("263.*") // maximum 2026.x (263.*)

        pluginDescription.set("""
            <h2>Property File Tool</h2>
            <p>A lightweight and efficient utility plugin designed to simplify working with configuration files in your IntelliJ projects.</p>

            <h3>Key Features:</h3>
            <ul>
                <li><b>YAML ↔ Properties Conversion</b>
                    <ul>
                        <li>Convert <code>.yml</code> files to <code>.properties</code></li>
                        <li>Convert <code>.properties</code> files to <code>.yml</code></li>
                    </ul>
                </li>
                <li><b>Merge & Clean Properties</b>
                    <ul>
                        <li>Merge multiple <code>.properties</code> files under the <code>resources</code> folder</li>
                        <li>Automatically remove duplicates and unused entries</li>
                    </ul>
                </li>
                <li><b>Validation & Checks</b>
                    <ul>
                        <li>Detect warnings and errors in property files</li>
                        <li>Improve configuration quality and consistency</li>
                    </ul>
                </li>
            </ul>

            <h3>How It Works:</h3>
            <p>
            Simply right-click on a single <code>.properties</code> file or a <code>resources</code> folder,
            and choose the desired action from the context menu. The plugin will automatically perform
            the selected operation.
            If right-click on source root folder, normally it's <code>java</code>, any unused property will show on the result window
            </p>

            <h3>Known Issues</h3>
            <p>You may have a <strong> IDE error occurred</strong> at the first run, it's an IntelliJ issue, just ignore it, try again.</p>

            <p>Boost your productivity and manage configuration files with ease!</p>
            <p>Supported IntelliJ versions: 2024.3 and later.</p>
        """.trimIndent())

        changeNotes.set("""
            <h3>Version 1.0.1</h3>
            <ul>
                <li>🎉 Fix unused property detection</li>
                <li>🔧 Improve cross‑reference analysis (Java PSI)</li>
                <li>🚀 Support for IntelliJ 2024.3+</li>
            </ul>
        """.trimIndent())

        version.set(project.version.toString())
    }
}
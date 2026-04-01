plugins {
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "org.cakk.unused-code-detector"
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

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("263.*")

        version.set(project.version.toString())
        pluginDescription.set("""
            <h2>Unused Code Detector</h2>
            <p>Detects unused classes, methods, and imports in your Java projects.</p>
            
            <h3>📊 Features</h3>
            <ul>
                <li>Detects unused classes in the project</li>
                <li>Identifies unused methods (including private methods)</li>
                <li>Finds unused imports in Java files</li>
                <li>Finds duplicate imports in Java files</li>
                <li>Provides quick fixes to remove unused code</li>
                <li>Shows results in a dedicated tool window</li>
                <li>export unused code list</li>
            </ul>
            
            <h3>🚀 Usage</h3>
            <ol>
                <li>Go to project <code>src</code> folder, right click and chose Analyze on top</li>
                <li>Click the plugin icon from bottom, review results in the tool window</li>
                <li>Double click to go to specific class, method or import package</li>
            </ol>
            <p>Supported IntelliJ versions: 2023.3 and later.</p>

        """.trimIndent())

        changeNotes.set("""
            <h3>Version 1.0.1</h3>
            <ul>
                <li>🎉 add unused variable feature</li>
            </ul>
        """.trimIndent())
    }
}
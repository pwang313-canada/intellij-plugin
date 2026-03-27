// build.gradle.kts
plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.0"
}

group = "org.cakk"
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
            <p>Real-time memory leak detection for IntelliJ IDEA.</p>
            <ul>
                <li>Monitors heap and old generation memory usage</li>
                <li>Detects rapid memory growth patterns</li>
                <li>Prompts user when rapid old gen growth detected</li>
                <li>Optional System.gc() invocation with user confirmation</li>
                <li>GC event analysis and efficiency tracking</li>
            </ul>
        """.trimIndent())

        changeNotes.set("""
            <h3>Version 1.0.0</h3>
            <ul>
                <li>Initial release</li>
                <li>Memory leak detection with user confirmation for GC</li>
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

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
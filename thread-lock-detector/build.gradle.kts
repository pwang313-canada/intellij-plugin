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
        sinceBuild.set("233")   // must match IDE major version
        untilBuild.set("253.*")

        version.set(project.version.toString())

        changeNotes.set("""
            <h3>Version 1.0.0</h3>
            <ul>
                <li>🎉 Add thread lock analysis feature</li>
                <li>✅ Unused variable detection</li>
            </ul>
        """.trimIndent())
    }

    // Optional: avoid buildSearchableOptions crash during first build
    buildSearchableOptions {
        enabled = false
    }
}
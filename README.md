# IntelliJ Code Quality Plugin

A multi‑module IntelliJ plugin that helps you detect common code quality issues in Java and property files. The plugin integrates seamlessly into the IDE, providing both static analysis and runtime monitoring for a safer, cleaner codebase.

## Modules

| Module                   | Description |
|--------------------------|-------------|
| **memory‑leak‑detector** | Detects potential memory leaks in Java code – e.g., unused collections, listeners not removed, static references to inner classes, etc. |
| **thread‑lock‑detector** | Performs static analysis of thread lock anti‑patterns (synchronized methods, locking on `this`, string literals, class objects) and includes a runtime deadlock monitor that attaches to a running JVM. |
| **property‑file-tool**   | Inspects `.properties` files for common issues: missing keys, duplicates, unused keys, incorrect formatting, and more. |
| **unused‑code‑detector** | Finds dead code – unused methods, fields, classes, and imports – and highlights them directly in the editor. |

All modules can be used independently; each contributes its own inspections and, where applicable, a dedicated tool window.

## Features

### Memory Leak Detector
- Highlights potential memory leaks directly in the editor.
- Provides quick‑fixes for common scenarios (e.g., removing unused listeners, replacing static references).
- Reports leaks in a dedicated tool window with navigation to the offending line.

### Thread Lock Detector
- **Static analysis:** Scans Java files for:
    - Synchronized methods that may hold locks longer than necessary
    - Synchronizing on `this`
    - Synchronizing on `String` literals
    - Synchronizing on `Class` objects
- **Runtime deadlock monitor:**
    - Attaches to any running Java process (by PID)
    - Polls every 5 seconds and displays full thread dumps when a deadlock is detected
    - Log area with clear‑log button and collapsible connection controls

### Property File Tool
- Validates `.properties` files for:
    - Duplicate keys
    - Missing keys referenced in code (if a properties‑key index is available)
    - Unused keys
    - Illegal characters and formatting
- Quick fixes to remove duplicates or rename keys

### Unused Code Detector
- Analyzes the whole project (or selected files) for dead code.
- Highlights unused methods, fields, classes, and imports with a dimmed effect.
- Can be run on demand or as a background inspection.

## Installation

1. Download the latest release JAR from the [Releases](https://github.com/your‑repo/releases) page (or build it yourself – see below).
2. In IntelliJ IDEA, go to **File → Settings → Plugins**.
3. Click the gear icon and select **Install Plugin from Disk…**.
4. Choose the downloaded JAR and restart the IDE.

## Usage

All modules add one or more **inspections** that run automatically as you type. Additionally, they provide on‑demand analysis actions and tool windows.

### Memory Leak Detector
- **On‑demand:** Right‑click a Java file or folder → **Analyze → Analyze Memory Leaks**. Results appear in the **Memory Leak Checker** tool window.
- **Automatic:** Issues are highlighted in the editor with a green underline and a light bulb for quick fixes.

### Thread Lock Detector
- **Static analysis:** Right‑click a Java file or folder → **Analyze → Analyze Thread Lock**. Results appear in the **Thread Lock Checker** tool window.
- **Runtime monitor:** Open the **Thread Lock Checker** tool window, click **Refresh** to list running Java processes, select one, click **Connect**, then **Start Monitoring**. Deadlocks will be shown in the lower log area.

### Property File Tool
- Inspects `.properties` files automatically when they are opened.
- Right‑click a properties file → **Analyze → Check Properties** to run a full scan.
- Results are shown in the **Properties Issues** tool window.

### Unused Code Detector
- Runs as a background inspection; unused code is dimmed in the editor.
- Right‑click a file or folder → **Analyze → Detect Unused Code** to see a full report in the **Unused Code** tool window.

## Requirements

- IntelliJ IDEA **2023.3** or later (Community or Ultimate)
- Java **11** or higher

## Building from Source

1. Clone the repository:
   ```bash
    git clone https://github.com/pwang313-canada/intellij-plugin   
   ```
2. Build all modules:
   ```
   ./gradlew clean buildPlugin
   ```
3. Build single module:
   ```
   ./gradlew  :unused-code-detector:buildPlugin
   ```


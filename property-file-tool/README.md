# Properties Merger & Converter

An IntelliJ IDEA plugin to help manage Spring Boot configuration files efficiently.

## Features

- **Convert between formats**: Easily convert application configuration files between `.properties` and `.yml`/`.yaml` formats.
- **Smart Properties Cleanup**:
    - Scans all `.properties` files under the `resources` folder.
    - Identifies **common properties** (keys that appear in **all** environment files with the **identical value**).
    - Moves common properties to `application.properties`.
    - Leaves environment-specific properties in their respective files (e.g. `application-dev.properties`, `application-uat.properties`, `application-prod.properties`, etc.).
    - Sorts all properties files alphabetically by key.
    - Removes duplicate entries within each file.
- **Validate property file**:
    - List all Error and Warnings
    - Double click file to edit the file


## How to Use

![how-to-use.png](src/main/resources/images/how-to-use.png)
### 1. Merge & Clean Properties Files
1. Right-click on the **`resources`** folder in your project.
2. Select **"Merge and Clean Properties Files"**.
3. Confirm the action in the dialog.
4. The plugin will:
    - Move common properties to `application.properties`
    - Remove those properties from environment-specific files
    - Sort all properties files alphabetically

### 2. Convert Between Properties and YAML
1. Right-click on the yml or properties files
2. Convert between properties and yml file

### 3. Validate properties file
1. Right-click on the properties file or <strong>resource</strong> folder
2. Property Tool -> Validate Properties
3. nAny properties file with issue will show on the bottom

## Requirements

- IntelliJ IDEA (Community or Ultimate)
- Spring Boot project with configuration files under `src/main/resources`

## Screenshots

## Future Enhancements

- Bidirectional conversion between `.properties` ↔ `.yml`/`.yaml`
- Support for `bootstrap.properties` / `bootstrap.yml`
- Option to treat properties with environment names in values as env-specific
- Customizable common property detection rules

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

## License

[MIT License](LICENSE)

---

Made with ❤️ for Spring Boot developers
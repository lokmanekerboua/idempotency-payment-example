# Setup Instructions

## Initial Setup

Since the Gradle wrapper files were not included, you need to initialize them:

```bash
# If you have Gradle installed globally:
gradle wrapper --gradle-version 8.11

# Or download the gradle-wrapper.jar from:
# https://raw.githubusercontent.com/gradle/gradle/v8.11.0/gradle/wrapper/gradle-wrapper.jar
# Place it in: gradle/wrapper/gradle-wrapper.jar
```

## Build the Project

```bash
# On Windows:
gradlew.bat clean build -x test

# On Linux/Mac:
./gradlew clean build -x test
```

## Run the Application

```bash
# On Windows:
gradlew.bat bootRun

# On Linux/Mac:
./gradlew bootRun
```

## Run Tests

```bash
# On Windows:
gradlew.bat test

# On Linux/Mac:
./gradlew test
```

## Docker Setup

```bash
docker compose up --build
```

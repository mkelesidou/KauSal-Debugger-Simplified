plugins {
    id("java") // Core Java plugin
    id("application") // For running the app
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    // Core dependencies
    implementation("com.google.guava:guava:32.0.1-jre") // Example utility library
    implementation("org.slf4j:slf4j-api:2.0.7") // Logging API
    implementation("ch.qos.logback:logback-classic:1.4.9") // Logging implementation
    implementation("com.github.javaparser:javaparser-core:3.25.4") // JavaParser for source code

    // Machine Learning (Random Forests)
    implementation("com.github.haifengl:smile-core:2.6.0")
    // Testing dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0") // JUnit 5 for tests
}

java {
    sourceCompatibility = JavaVersion.toVersion(21)
    targetCompatibility = JavaVersion.toVersion(21)
}

tasks.test {
    useJUnitPlatform() // Ensures compatibility with JUnit 5
    testLogging {
        // Show standard output and error
        showStandardStreams = true
    }
}

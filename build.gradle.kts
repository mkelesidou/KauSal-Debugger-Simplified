plugins {
    id("java") // Core Java plugin
    id("application") // For running the app
    id("com.github.johnrengelman.shadow") version "8.1.1" // For creating a fat JAR
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://soot-build.cs.uni-paderborn.de/nexus/repository/soot-releases/")
}

dependencies {
    // Core dependencies
    implementation("com.google.guava:guava:32.0.1-jre") // Example utility library
    implementation("org.slf4j:slf4j-api:2.0.7") // Logging API
    implementation("ch.qos.logback:logback-classic:1.4.9") // Logging implementation
    implementation("com.github.javaparser:javaparser-core:3.25.4") // JavaParser for source code
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.4")
    implementation("ch.qos.logback:logback-classic:1.2.3")


    // CDG extraction library
    implementation ("ca.mcgill.sable:soot:4.1.0")
    // Machine Learning (Random Forests)
    implementation ("com.github.haifengl:smile-core:2.6.0")
    implementation ("com.github.haifengl:smile-data:2.6.0")
    implementation ("com.github.haifengl:smile-io:2.6.0")
    implementation("com.google.code.gson:gson:2.10")

    // Testing dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2") // JUnit 5 for tests
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.2") // JUnit 5 for tests
    
    // Mockito for mocking in tests
    testImplementation("org.mockito:mockito-core:5.4.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.4.0")

}

// Application configuration
application {
    // Set the main class for the application
    mainClass.set("sanalysis.CFGGenerator")
}

java {
    sourceCompatibility = JavaVersion.toVersion(21)
    targetCompatibility = JavaVersion.toVersion(21)
}

tasks.test {
    useJUnitPlatform() // Ensures compatibility with JUnit 5
}

// Add task to print classpath
tasks.register("printClasspath") {
    doLast {
        println(sourceSets.main.get().runtimeClasspath.asPath)
    }
}

// Configure the Shadow JAR
tasks.shadowJar {
    archiveBaseName.set("unival")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}

// Task to copy dependencies to a directory
tasks.register<Copy>("copyDependencies") {
    from(configurations.implementation)
    into("build/dependencies")
}

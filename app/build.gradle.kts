plugins {
    application
    id("jacoco")
    id("java")
}

jacoco {
    toolVersion = "0.8.10"
}

repositories {
    mavenCentral()
}

dependencies {
    // Use JUnit Jupiter for testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Application dependencies
    implementation("com.google.guava:guava:32.1.2-jre")
    implementation("fr.inria.gforge.spoon:spoon-core:10.3.0")
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-util:9.5")

    // Optional ML libraries
    // implementation("nz.ac.waikato.cms.weka:weka-stable:3.8.6")
    // implementation("com.github.haifengl:smile-core:2.6.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    // Define the main class for the application
    mainClass.set("org.example.App")
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests
    useJUnitPlatform()
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

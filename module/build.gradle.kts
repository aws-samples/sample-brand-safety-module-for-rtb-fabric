plugins {
    kotlin("jvm") version "2.0.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    // AWS Lambda + S3 (only needed for the LambdaHandler entry point)
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    implementation(platform("software.amazon.awssdk:bom:2.54.11"))
    implementation("software.amazon.awssdk:s3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // Local CLI entry point
    mainClass.set("com.example.brandsafety.MainKt")
}

// Pin the build to Java 17 via a toolchain, so both Kotlin and Java compile against
// JDK 17 regardless of the JDK on the machine. Gradle downloads/locates a matching
// JDK automatically.
kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

// Build a fat jar for the Lambda deployment package.
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}

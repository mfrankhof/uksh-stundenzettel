plugins {
    id("java")
    id("application")
}

group = "com.frankhof"
version = "2026.1.0"

application {
    mainClass = "UkshStundenzettel"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation(platform("org.apache.logging.log4j:log4j-bom:2.26.0"))
    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val projectVersion = project.version
    filesMatching("version.properties") {
        expand("version" to projectVersion)
    }
}
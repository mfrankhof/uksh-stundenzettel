plugins {
    id("application")
    id("org.openjfx.javafxplugin").version("0.1.0")
}

group = "com.frankhof"
version = "2026.1.1"

application {
    mainClass = "MainApp"
    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation(platform("org.apache.logging.log4j:log4j-bom:2.26.0"))
    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")
    annotationProcessor(platform("org.apache.logging.log4j:log4j-bom:2.26.0"))
    annotationProcessor("org.apache.logging.log4j:log4j-core")
    implementation("org.apache.pdfbox:pdfbox:3.0.7")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

javafx {
    version = "26.0.1"
    modules = listOf("javafx.controls", "javafx.fxml")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf(
        "-Alog4j.graalvm.groupId=${project.group}",
        "-Alog4j.graalvm.artifactId=${project.name}"
    ))
}

tasks.processResources {
    val projectVersion = project.version
    filesMatching("version.properties") {
        expand("version" to projectVersion)
    }
}
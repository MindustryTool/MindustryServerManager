import org.gradle.jvm.toolchain.JavaLanguageVersion

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("Anuken:Mindustry:${property("mindustryVersion")}")
    compileOnly("org.projectlombok:lombok:1.18.30")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.16.2")
    compileOnly("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.2")

    annotationProcessor("org.projectlombok:lombok:1.18.30")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

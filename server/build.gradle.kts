import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    application
    id("com.gradleup.shadow") version "8.3.10"
}

application {
    mainClass.set("server.ServerMain")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.2")
    implementation("io.javalin:javalin:6.7.0")

    implementation("com.github.docker-java:docker-java-core:3.4.1")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    implementation("com.auth0:java-jwt:4.4.0")
    implementation("org.modelmapper:modelmapper:3.1.0")
    implementation(project(":dto"))

    implementation("Anuken:Mindustry:${property("mindustryVersion")}")

    testImplementation(project(":plugin"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.43.2.0")

    compileOnly("org.projectlombok:lombok:1.18.30")

    annotationProcessor("org.projectlombok:lombok:1.18.30")
}

configurations {
    compileOnly {
        extendsFrom(configurations.getByName("annotationProcessor"))
    }
}

tasks.test {
    useJUnitPlatform()
}

configurations.all {
    exclude(group = "commons-logging", module = "commons-logging")
}

tasks.named("jar") {
    dependsOn(":dto:jar")
    enabled = false
}

tasks.named<Jar>("shadowJar") {
    archiveFileName.set("application.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.EC",
        "META-INF/INDEX.LIST"
    )

    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }

    dependsOn(":dto:jar")
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

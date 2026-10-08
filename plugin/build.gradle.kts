
import org.gradle.jvm.toolchain.JavaLanguageVersion

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

sourceSets {
    main {
        java {
            srcDir("src/main/java")
        }
    }
}


tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.2")
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
    implementation("org.xerial:sqlite-jdbc:3.43.2.0")

    implementation(project(":annotation"))
    implementation(project(":database"))
    implementation(project(":gateway"))
    implementation(project(":common"))

    compileOnly("org.projectlombok:lombok:1.18.30")
    compileOnly("Anuken:Mindustry:${property("mindustryVersion")}")

    annotationProcessor("org.projectlombok:lombok:1.18.30")
    annotationProcessor(project(":annotation"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("Anuken:Mindustry:${property("mindustryVersion")}")
}

tasks.test {
    useJUnitPlatform()
}

// Thin jar stays default for IDE use. Fat pack runs only at pack time.
tasks.register<Jar>("pluginFatJar") {
    dependsOn(
        ":annotation:classes",
        ":database:classes",
        ":gateway:classes",
        ":common:classes"
    )

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("plugin.jar")

    // Current project
    from(sourceSets.main.get().output)

    // Internal modules
    from(project(":annotation").sourceSets.main.get().output)
    from(project(":database").sourceSets.main.get().output)
    from(project(":gateway").sourceSets.main.get().output)
    from(project(":common").sourceSets.main.get().output)

    // External dependencies only
    configurations.runtimeClasspath.get()
        .filter { dependency ->
            dependency.name !in listOf(
                "annotation-${project.version}.jar",
                "database-${project.version}.jar",
                "gateway-${project.version}.jar",
                "common-${project.version}.jar"
            )
        }
        .forEach { dependency ->
            from(if (dependency.isDirectory) dependency else zipTree(dependency))
        }

    exclude(
        "org/sqlite/native/Windows/**",
        "org/sqlite/native/Mac/**",
        "org/sqlite/native/FreeBSD/**",
        "org/sqlite/native/Linux-Android/**",
        "org/sqlite/native/Linux/**",
        "org/sqlite/native/Linux-Musl/aarch64/**",
        "org/sqlite/native/Linux-Musl/x86/**",
        "plugin/processor/**",
        "META-INF/services/javax.annotation.processing.Processor"
    )

    from(project.projectDir) {
        include("plugin.json")
    }
}

tasks.named("assemble") {
    dependsOn("pluginFatJar")
}

tasks.named("build") {
    dependsOn("pluginFatJar")
}

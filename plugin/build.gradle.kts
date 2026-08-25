
java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
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
    implementation("com.neovisionaries:nv-websocket-client:2.14")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.2")
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
    implementation("org.xerial:sqlite-jdbc:3.43.2.0")

    implementation(project(":dto"))
    implementation(project(":annotation"))
    implementation(project(":graph"))

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

tasks.jar {
    dependsOn(":dto:classes")
    dependsOn(":graph:classes")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("${project.name}.jar")

    from(configurations.getByName("runtimeClasspath").files.map { if (it.isDirectory) it else zipTree(it) })

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

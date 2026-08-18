plugins {
    `java-library`
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--release", "8"))
    options.encoding = "UTF-8"
}

sourceSets {
    main {
        java {
            srcDirs("src")
        }
    }
}

dependencies {
    compileOnly("Anuken:Mindustry:${property("mindustryVersion")}")
    compileOnly("org.projectlombok:lombok:1.18.30")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.16.2")
    compileOnly("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.2")

    annotationProcessor("org.projectlombok:lombok:1.18.30")
}

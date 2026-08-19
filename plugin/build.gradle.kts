
java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

val generatedRegistryDir = layout.buildDirectory.dir("generated/registry/java")

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            srcDir(generatedRegistryDir)
        }
    }
}

tasks.register("generateComponentRegistry") {
    val srcRoot = file("src/main/java")
    val outDir = generatedRegistryDir.get().asFile
    inputs.dir(srcRoot)
    outputs.dir(outDir)
    outDir.mkdirs()

    doLast {
        val components = mutableListOf<String>()
        srcRoot.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(".java")) {
                val text = f.readText()
                val pkg = Regex("""package\s+([\w.]+)\s*;""").find(text)?.groupValues?.get(1) ?: ""
                val parts = text.split(Regex("""(?m)^\s*@Component\b"""))
                for (i in 1 until parts.size) {
                    val m = Regex("""(?m)^\s*(?:public\s+)?(?:final\s+|abstract\s+)?(?:class|interface|@interface|enum|record)\s+(\w+)""")
                        .find(parts[i])
                    if (m != null) {
                        components.add("$pkg.${m.groupValues[1]}")
                    }
                }
            }
        }

        val uniqueComponents = components.sorted().distinct()
        val body = uniqueComponents.joinToString("\n") { "        ${it}.class," }

        outDir.mkdirs()
        val genDir = File(outDir, "plugin/core")
        genDir.mkdirs()
        File(genDir, "ComponentRegistry.java").writeText(
            """
package plugin.core;

import java.util.List;

public final class ComponentRegistry {

    public static final Class<?>[] COMPONENTS = {
$body
    };

    private ComponentRegistry() {
    }
}
""".trimIndent()
        )
    }
}

tasks.named("compileJava") {
    dependsOn("generateComponentRegistry")
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

    compileOnly("org.projectlombok:lombok:1.18.30")
    compileOnly("Anuken:Mindustry:${property("mindustryVersion")}")

    annotationProcessor("org.projectlombok:lombok:1.18.30")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("Anuken:Mindustry:${property("mindustryVersion")}")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    dependsOn(":dto:jar")
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
        "org/sqlite/native/Linux-Musl/x86/**"
    )

    from(project.projectDir) {
        include("plugin.json")
    }
}

allprojects {
    group = "mindustrytool"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
        google()

        // Downloads the dependencies JAR file from Mindustry releases; does not use any real repository. Surprisingly, this is the most reliable option.
        ivy {
            url = uri("https://github.com/")
            patternLayout {
                artifact("/[organisation]/[module]/releases/download/[revision]/dependencies.jar")
            }
            metadataSources {
                artifact()
            }
        }

        // If the version is set to 'latest', downloads the latest Mindustry *release* as a dependency
        ivy {
            url = uri("https://github.com/")
            patternLayout {
                artifact("/[organisation]/[module]/releases/[revision]/download/dependencies.jar")
            }
            metadataSources {
                artifact()
            }
        }

        // For depending on the absolute newest commit for Mindustry
        ivy {
            url = uri("https://github.com/")
            patternLayout {
                artifact("/[organisation]/[module]/releases/download/master/[revision].jar")
            }
            metadataSources {
                artifact()
            }
        }
    }
}

subprojects {
    apply(plugin = "java")

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    tasks.register("checkNoFullyQualifiedNames") {
        group = "verification"
        description = "Fails on fully qualified class names in code. Use imports instead."
        doLast {
            fun stripStringsAndComments(src: String): String {
                val out = StringBuilder(src.length)
                var i = 0
                var state = 0
                while (i < src.length) {
                    when (state) {
                        0 -> {
                            if (src.startsWith("//", i)) { state = 1; out.append("  "); i += 2 }
                            else if (src.startsWith("/*", i)) { state = 2; out.append("  "); i += 2 }
                            else if (src.startsWith("\"\"\"", i)) { state = 5; out.append("   "); i += 3 }
                            else if (src[i] == '"') { state = 3; out.append(' '); i++ }
                            else if (src[i] == '\'') { state = 4; out.append(' '); i++ }
                            else { out.append(src[i]); i++ }
                        }
                        1 -> {
                            if (src[i] == '\n') { state = 0; out.append('\n') } else { out.append(' ') }
                            i++
                        }
                        2 -> {
                            if (src.startsWith("*/", i)) { state = 0; out.append("  "); i += 2 }
                            else { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
                        }
                        3 -> {
                            if (src[i] == '\\' && i + 1 < src.length) { out.append("  "); i += 2 }
                            else if (src[i] == '"') { state = 0; out.append(' '); i++ }
                            else { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
                        }
                        4 -> {
                            if (src[i] == '\\' && i + 1 < src.length) { out.append("  "); i += 2 }
                            else if (src[i] == '\'') { state = 0; out.append(' '); i++ }
                            else { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
                        }
                        else -> {
                            if (src.startsWith("\"\"\"", i)) { state = 0; out.append("   "); i += 3 }
                            else { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
                        }
                    }
                }
                return out.toString()
            }
            val fqn = Regex("(?<!\\w)(java|javax|jakarta|org|com|net|io|mindustry|arc|common|plugin|server|gateway)(\\.[a-z0-9_]+)+\\.([A-Z][\\w$]*)")
            val importDecl = Regex("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+);")
            val failures = mutableListOf<String>()
            val javaFiles = projectDir.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .filter { f ->
                    val rel = f.relativeTo(projectDir).path.replace('\\', '/')
                    !rel.contains("/build/") && !rel.startsWith("build/") &&
                        !rel.contains("/bin/") && !rel.startsWith("bin/") &&
                        !rel.contains("/.gradle/") && !rel.contains("/out/")
                }.toList()
            for (file in javaFiles) {
                val rel = file.relativeTo(projectDir).path.replace('\\', '/')
                val original = file.readText()
                val imports = importDecl.findAll(original).map { it.groupValues[1] }.toSet()
                val code = stripStringsAndComments(original)
                val lines = code.lines()
                for (lineIndex in lines.indices) {
                    val line = lines[lineIndex]
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue
                    for (m in fqn.findAll(line)) {
                        val full = m.value
                        val simple = m.groupValues[3]
                        val pkg = full.substringBeforeLast('.')
                        val importedSameSimple = imports.any { it.substringAfterLast('.') == simple }
                        val importedSameFqn = imports.contains(full)
                        if (importedSameSimple && !importedSameFqn) continue
                        if (pkg == "java.lang") {
                            failures.add("$rel:${lineIndex + 1}: $full (use simple name, java.lang needs no import)")
                        } else {
                            failures.add("$rel:${lineIndex + 1}: $full (add import and use $simple)")
                        }
                    }
                }
            }
            if (failures.isNotEmpty()) {
                throw GradleException(
                    "Fully qualified class names found (${failures.size}). Use imports instead:\n" +
                        failures.joinToString("\n")
                )
            }
        }
    }

    tasks.named("check") {
        dependsOn("checkNoFullyQualifiedNames")
    }
}


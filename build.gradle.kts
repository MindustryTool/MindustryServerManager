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
}


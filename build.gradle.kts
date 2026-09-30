plugins {
    `maven-publish`
    id("hytale-mod") version "0.8.1"
}

group = "com.hexvane"
version = "1.0.0"
val javaVersion = 25
val embeddedLibraries by configurations.creating
configurations.implementation { extendsFrom(embeddedLibraries) }

repositories {
    mavenCentral()
    maven("https://maven.hytale-modding.info/releases") {
        name = "HytaleModdingReleases"
    }
    maven("https://cursemaven.com") {
        name = "CurseMaven"
    }
}

dependencies {
    add(embeddedLibraries.name, "org.postgresql:postgresql:42.7.13") { isTransitive = false }
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output.resourcesDir)
    from(embeddedLibraries.map { zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

tasks.register("verifyReleaseJar") {
    group = "verification"
    description = "Fails if the release jar accidentally bundles HytaleServer or other blocked packages."
    dependsOn(tasks.jar)
    val releaseJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(releaseJar)
    doLast {
        val jarFile = releaseJar.get().asFile
        if (!jarFile.isFile) {
            error("Missing release jar: ${jarFile.absolutePath}")
        }
        val blocked =
            listOf(
                "com/hypixel/hytale/Main.class",
                "org/bouncycastle/",
                "native/win-x64/quiche.dll",
            )
        val jarExe =
            File(System.getProperty("java.home"), "bin/jar.exe").takeIf { it.isFile }
                ?: File(System.getProperty("java.home"), "bin/jar").takeIf { it.isFile }
        if (jarExe == null) {
            logger.lifecycle("verifyReleaseJar: jar tool not found; skipped content checks")
            return@doLast
        }
        val listing =
            ProcessBuilder(jarExe.absolutePath, "tf", jarFile.absolutePath)
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        for (pattern in blocked) {
            if (listing.contains(pattern)) {
                error(
                    "Release jar ${jarFile.name} contains $pattern — do not embed HytaleServer on runtimeClasspath merge"
                )
            }
        }
        for (required in listOf("org/postgresql/Driver.class", "org/postgresql/core/v3/ConnectionFactoryImpl.class")) {
            if (!listing.lineSequence().any { it.trim() == required }) {
                error("Release jar ${jarFile.name} is missing its PostgreSQL driver: $required")
            }
        }
        val sizeMb = jarFile.length() / (1024.0 * 1024.0)
        logger.lifecycle("verifyReleaseJar: ${jarFile.name} OK (${"%.1f".format(sizeMb)} MB, no HytaleServer)")
    }
}

hytale {
    // Uncomment to add Assets.zip to external libraries (very large; IDE may become slow).
    //
    // addAssetsDependency = true

    // Uncomment to develop against the pre-release version of the game.
    //
    // updateChannel = "pre-release"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal", "-Xlint:unchecked"))
}

tasks.test {
    useJUnitPlatform()
    // Native DTO and codec tests use the installed server API without embedding it in the release jar.
    classpath += sourceSets.main.get().compileClasspath
}

tasks.named<ProcessResources>("processResources") {
    var replaceProperties = mapOf(
        "plugin_group" to findProperty("plugin_group"),
        "plugin_maven_group" to project.group,
        "plugin_name" to project.name,
        "plugin_version" to project.version,
        "server_version" to findProperty("server_version"),

        "plugin_description" to findProperty("plugin_description"),
        "plugin_website" to findProperty("plugin_website"),

        "plugin_main_entrypoint" to findProperty("plugin_main_entrypoint"),
        "plugin_author" to findProperty("plugin_author")
    )

    filesMatching("manifest.json") {
        expand(replaceProperties)
    }

    inputs.properties(replaceProperties)
}

tasks.withType<Jar> {
    manifest {
        attributes["Specification-Title"] = rootProject.name
        attributes["Specification-Version"] = version
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] =
            providers.environmentVariable("COMMIT_SHA_SHORT")
                .map { "${version}-${it}" }
                .getOrElse(version.toString())
    }
}

publishing {
    repositories {
        // Put publish repositories here, not dependency repositories.
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

tasks.register<Copy>("syncAssets") {
    group = "hytale"
    description = "Explicitly import in-game asset edits from build/resources/main into source; may overwrite source edits. Never runs automatically."

    from(layout.buildDirectory.dir("resources/main"))
    into("src/main/resources")
    exclude("manifest.json")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    doLast {
        println("Assets successfully synced from game to source code.")
    }
}

afterEvaluate {
    val runServerTask = tasks.findByName("runServer") ?: tasks.findByName("server")
    if (runServerTask == null) {
        logger.warn("Could not find 'runServer' or 'server' task (hytale-mod); development server aliases unavailable.")
        return@afterEvaluate
    }
    if (runServerTask !is JavaExec) {
        logger.warn("Task '${runServerTask.name}' is not JavaExec; skipping development server aliases.")
        return@afterEvaluate
    }
    val runServer = runServerTask as JavaExec
    runServer.jvmArgs = runServer.jvmArgs.filter { it.isNotBlank() }
    // Gradle launch tasks are development entry points. Keep the shipped plugin's
    // production default, and preserve an explicitly configured production environment.
    if (!runServer.environment.containsKey("ETERNIA_MODE")) {
        runServer.environment("ETERNIA_MODE", "local")
    }
    // Never copy build output over source on shutdown: source may have changed
    // while this server was running. Import intentional in-game edits explicitly.
    logger.lifecycle("Task '${runServer.name}' leaves source assets unchanged on exit; syncAssets is manual only.")

    tasks.register<JavaExec>("runServerNoSync") {
        group = "hytale"
        description =
            "Compatibility alias for runServer; both leave source assets unchanged on exit."
        classpath = runServer.classpath
        mainClass = runServer.mainClass
        mainModule = runServer.mainModule
        modularity.inferModulePath = runServer.modularity.inferModulePath
        jvmArgs = runServer.jvmArgs.filter { it.isNotBlank() }
        workingDir = runServer.workingDir
        args = runServer.args
        systemProperties = runServer.systemProperties
        environment = runServer.environment
        standardInput = runServer.standardInput
        isIgnoreExitValue = runServer.isIgnoreExitValue
        javaLauncher = runServer.javaLauncher
        enableAssertions = runServer.enableAssertions
    }
    logger.lifecycle("Task 'runServerNoSync' registered (no post-exit asset sync).")
    val localServer = tasks.register<JavaExec>("runServerLocal") {
        group = "hytale"
        description = "Authenticated loopback development server at 127.0.0.1:5523 in run-local, with durable local storage and no asset sync."
        dependsOn(tasks.classes)
        classpath = runServer.classpath
        mainClass = runServer.mainClass
        mainModule = runServer.mainModule
        modularity.inferModulePath = runServer.modularity.inferModulePath
        jvmArgs = runServer.jvmArgs.filter { it.isNotBlank() && !it.startsWith("-XX:AOT") && !it.startsWith("-XX:SharedArchiveFile") }
        val nativeSmoke = providers.gradleProperty("nativeSmoke").isPresent
        workingDir = if (nativeSmoke) layout.buildDirectory.dir("native-smoke/${System.currentTimeMillis()}").get().asFile else layout.projectDirectory.dir("run-local").asFile
        val localArgs = mutableListOf<String>()
        val replaced = setOf("--bind", "-b", "--auth-mode", "--universe", "--session-token", "--identity-token")
        var skipValue = false
        for (arg in runServer.args) {
            if (skipValue) { skipValue = false; continue }
            if (arg in replaced) { skipValue = true; continue }
            if (replaced.any { arg.startsWith("$it=") } || arg == "--allow-op" || arg == "--disable-sentry") continue
            localArgs.add(arg)
        }
        // Offline auth rejects multiplayer clients, even when server OAuth is present.
        // Only the headless smoke run should use it.
        val authMode = if (nativeSmoke) "offline" else "authenticated"
        args = localArgs + listOf("--bind", "127.0.0.1:5523", "--auth-mode", authMode, "--allow-op", "--disable-sentry")
        systemProperties = runServer.systemProperties
        environment = runServer.environment
        environment("ETERNIA_MODE", "local")
        if (nativeSmoke) environment("ETERNIA_NATIVE_SMOKE", "1")
        environment("ETERNIA_BRIDGE_ADDRESS", "127.0.0.1")
        standardInput = System.`in`
        javaLauncher = runServer.javaLauncher
        enableAssertions = runServer.enableAssertions
        doFirst {
            workingDir.mkdirs()
            if (!nativeSmoke) logger.lifecycle("Eternia local playtest: connect to 127.0.0.1:5523 (authenticated; ${if (environment["ETERNIA_DATABASE_URL"].toString().startsWith("jdbc:postgresql://")) "PostgreSQL" else "local file"} storage).")
        }
    }
    tasks.register<JavaExec>("runServerPostgres") {
        group = "hytale"
        description = "Isolated PostgreSQL playtest at 127.0.0.1:5524; start through scripts/postgres-local.ps1 Game."
        dependsOn(tasks.classes)
        val local = localServer.get()
        classpath = local.classpath
        mainClass = local.mainClass
        mainModule = local.mainModule
        modularity.inferModulePath = local.modularity.inferModulePath
        jvmArgs = local.jvmArgs
        args = local.args.map { if (it == "127.0.0.1:5523") "127.0.0.1:5524" else it }
        systemProperties = local.systemProperties
        environment = local.environment
        workingDir = layout.projectDirectory.dir("run-postgres").asFile
        standardInput = System.`in`
        javaLauncher = local.javaLauncher
        enableAssertions = local.enableAssertions
        doFirst {
            require(!providers.gradleProperty("nativeSmoke").isPresent) { "PostgreSQL playtests must use authenticated multiplayer; nativeSmoke is a separate file-backed test." }
            require(environment["ETERNIA_DATABASE_URL"].toString().startsWith("jdbc:postgresql://")) { "Run scripts/postgres-local.ps1 Game to configure the isolated local PostgreSQL database." }
            workingDir.mkdirs()
            logger.lifecycle("Eternia PostgreSQL playtest: connect to 127.0.0.1:5524 (run-postgres world; authenticated).")
        }
    }
}

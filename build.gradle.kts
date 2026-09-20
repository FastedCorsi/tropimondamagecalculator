import java.security.MessageDigest

plugins {
    id("fabric-loom") version "1.15.5"
    id("maven-publish")
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
}

// Build-only tooling; it never enters the mod's runtime classpath or JAR.
val privacy = sourceSets.create("privacy") {
    java.setSrcDirs(listOf("tools/privacy/src"))
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}
dependencies { testImplementation(privacy.output) }

fun JavaExec.privacyCheck(vararg arguments: Any) {
    group = "verification"
    dependsOn(privacy.classesTaskName)
    classpath = privacy.runtimeClasspath
    mainClass.set("fr.tropimon.privacy.DistributionPrivacyCheck")
    workingDir(projectDir)
    args(projectDir.absolutePath, *arguments)
    outputs.upToDateWhen { false }
}

val verifyPrivacy = tasks.register<JavaExec>("verifyPrivacy") {
    description = "Check tracked and new shareable files without printing sensitive values."
    privacyCheck("sources")
}
val verifyModPrivacy = tasks.register<JavaExec>("verifyModPrivacy") {
    description = "Inspect the final remapped JAR, constants, metadata and resources."
    privacyCheck("archives")
    dependsOn(tasks.remapJar)
    doFirst { args(tasks.remapJar.get().archiveFile.get().asFile.absolutePath) }
}
val verifySourcesPrivacy = tasks.register<JavaExec>("verifySourcesPrivacy") {
    privacyCheck("archives")
    dependsOn(tasks.remapSourcesJar)
    doFirst { args(tasks.remapSourcesJar.get().archiveFile.get().asFile.absolutePath) }
}
val sourceDistribution = tasks.register<JavaExec>("sourceDistribution") {
    description = "Package only privacy-checked shareable files, never the working directory."
    privacyCheck("source-zip", layout.buildDirectory.file("distributions/tropimon-damage-calc-${project.version}-project.zip").get().asFile.absolutePath)
    dependsOn(verifyPrivacy)
}
val verifyDistribution = tasks.register<JavaExec>("verifyDistribution") {
    privacyCheck("archives", layout.buildDirectory.file("distributions/tropimon-damage-calc-${project.version}-project.zip").get().asFile.absolutePath)
    dependsOn(verifyPrivacy, verifyModPrivacy, verifySourcesPrivacy, sourceDistribution)
}
tasks.register<JavaExec>("verifyGitIdentity") {
    description = "Verify the effective public author and committer before an authorized commit; never change Git config."
    privacyCheck("git-identity")
}
tasks.jar { dependsOn(verifyPrivacy) }
tasks.named("sourcesJar") { dependsOn(verifyPrivacy) }
tasks.remapJar { finalizedBy(verifyModPrivacy) }
tasks.remapSourcesJar { finalizedBy(verifySourcesPrivacy) }
tasks.check { dependsOn(verifyPrivacy) }
tasks.build { dependsOn(verifyDistribution) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("damagecalc.benchmark", System.getProperty("damagecalc.benchmark", "false"))
}

// Opt-in production-runtime verification. No installed JAR is modified, and the harness is not shipped.
if (providers.gradleProperty("verificationMods").isPresent) {
    val localMods = file(providers.gradleProperty("verificationMods").get())
    val coexistence = providers.gradleProperty("coexistence").isPresent
    val smoke = sourceSets.create("smoke") {
        compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    }
    val smokeJar = tasks.register<Jar>("smokeJar") {
        from(smoke.output)
        archiveClassifier.set("smoke-dev")
    }
    val remapSmoke = tasks.register<net.fabricmc.loom.task.RemapJarTask>("remapSmokeJar") {
        inputFile.set(smokeJar.flatMap { it.archiveFile })
        archiveClassifier.set("smoke")
        addNestedDependencies.set(false)
    }
    tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runSmoke") {
        mods.from(tasks.remapJar.flatMap { it.archiveFile }, remapSmoke.flatMap { it.archiveFile })
        mods.from(fileTree(localMods) {
            include("Cobblemon-fabric-*.jar", "fabric-language-kotlin-*.jar", "fabric-api-*.jar")
            if (coexistence) {
                include("TropimonUIBattle-*.jar", "TropimonTeamBuilder-*.jar", "TropimonTeamHunt-*.jar",
                        "TropimonBidMaker-*.jar", "TropimonCatchPreview-*.jar", "TropimonChatFilter-*.jar",
                        "TropimonStocksManager-*.jar", "TropimodClient-*.jar", "XaerosWorldMap*.jar",
                        "mega_showdown-*.jar", "architectury-*.jar", "geckolib-*.jar", "trinkets-*.jar")
            }
        })
        runDir.set(layout.buildDirectory.dir("verification/" + if (coexistence) "coexistence" else "isolated"))
        jvmArgs.addAll("-Xmx3G", "-Ddamagecalc.smoke.coexistence=" + coexistence)
        programArgs.addAll("--width", "1380", "--height", "900")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        doFirst { runDir.file("smoke-passed.txt").get().asFile.delete() }
        doLast {
            check(runDir.file("smoke-passed.txt").get().asFile.isFile) { "Runtime smoke did not pass; inspect the isolated latest.log" }
        }
    }
}

val cobblemonMinimumVersion = property("cobblemon_min_version") as String
val verifyCobblemonCompatibility = tasks.register("verifyCobblemonCompatibility") {
    group = "verification"
    description = "Refuse les anciennes bornes Cobblemon avant de fabriquer un JAR."
    inputs.property("cobblemonMinimumVersion", cobblemonMinimumVersion)
    inputs.file("src/main/resources/fabric.mod.json")
    doLast {
        val expected = "\"cobblemon\": \">=$cobblemonMinimumVersion\""
        check(file("src/main/resources/fabric.mod.json").readText().contains(expected)) {
            "fabric.mod.json doit déclarer Cobblemon >=$cobblemonMinimumVersion sans borne maximale artificielle."
        }
    }
}

tasks.processResources {
    dependsOn(verifyCobblemonCompatibility)
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

val prepareReleaseDelivery = tasks.register("prepareReleaseDelivery") {
    group = "distribution"
    description = "Produit les JAR local et partageable vérifiés de la même version."
    dependsOn(tasks.build)
    doLast {
        val source = tasks.remapJar.get().archiveFile.get().asFile
        val deliveryRoot = layout.buildDirectory.dir("release").get().asFile
        val shareDirectory = deliveryRoot.resolve("shareable")
        val localDirectory = deliveryRoot.resolve("local")
        shareDirectory.deleteRecursively()
        localDirectory.deleteRecursively()
        shareDirectory.mkdirs()
        localDirectory.mkdirs()

        fun copyAndHash(target: File) {
            source.copyTo(target, overwrite = true)
            val digest = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            target.resolveSibling(target.name + ".sha256").writeText(hash + System.lineSeparator())
        }

        copyAndHash(shareDirectory.resolve("TropimonDamageCalc-${project.version}+1.21.1.jar"))
        copyAndHash(localDirectory.resolve("TropimonDamageCalc-${project.version}+1.21.1-LOCAL.jar"))
        file("tools/install-local-deferred.ps1")
            .copyTo(localDirectory.resolve("install-local-deferred.ps1"), overwrite = true)
        file("tools/InstallManagedLocalMod.ps1")
            .copyTo(localDirectory.resolve("InstallManagedLocalMod.ps1"), overwrite = true)
    }
}

tasks.register("armReleaseLocal") {
    group = "distribution"
    description = "Arme l'installation locale différée sans arrêter Minecraft ni le launcher."
    dependsOn(prepareReleaseDelivery)
    doLast {
        val script = layout.buildDirectory.file("release/local/install-local-deferred.ps1").get().asFile
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
            .directory(script.parentFile)
            .start()
    }
}



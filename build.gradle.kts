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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.register<Copy>("installTropimonLocal") {
    dependsOn(tasks.remapJar)
    val tropimonMods = file("${System.getProperty("user.home")}/AppData/Roaming/.tropimon/mods")
    doFirst {
        project.delete(fileTree(tropimonMods) {
            include("TropimonDamageCalc-*.jar")
        })
    }
    from(tasks.remapJar.flatMap { it.archiveFile })
    into(tropimonMods)
    rename { "TropimonDamageCalc-${project.version}+1.21.1-LOCAL.jar" }
}

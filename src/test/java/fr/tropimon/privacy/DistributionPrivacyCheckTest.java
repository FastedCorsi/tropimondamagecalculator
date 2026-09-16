package fr.tropimon.privacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class DistributionPrivacyCheckTest {
    @TempDir Path temp;

    @Test void acceptsCanonicalCreditAndPreservesFunctionalPublicUrls() throws Exception {
        var check = check();
        check.scan("fabric.mod.json", metadata("By FastedCorsi"), 0);
        check.scanText("api.java", "https://rankedapi.tropimon.fr/api/species");
        check.scanText("assets.md", "Original artwork rights remain with their authors.");
        assertTrue(check.findings().isEmpty());
    }

    @Test void rejectsWrongDeveloperCreditWithoutEchoingTheValue() throws Exception {
        var check = check();
        check.scan("fabric.mod.json", metadata("Synthetic Developer"), 0);
        assertTrue(has(check, "noncanonical-author"));
        assertFalse(check.findings().toString().contains("Synthetic Developer"));
    }

    @Test void preservesThirdPartyMetadataAndLicenses() throws Exception {
        var check = check();
        check.scan("third-party/fabric.mod.json", bytes("{\"id\":\"official_dependency\",\"authors\":[\"Example Contributors\"]}"), 0);
        check.scan("LICENSE", bytes("Copyright Example Contributors. Apache License, Version 2.0."), 0);
        assertTrue(check.findings().isEmpty());
    }

    @Test void findsPortablePlatformSpecificAndEscapedHomePaths() {
        for (String path : List.of(String.join("/", "C:", "Users", "SyntheticAccount", "work"),
                String.join("\\", "C:", "Users", "SyntheticAccount", "work"),
                String.join("\\\\", "C:", "Users", "SyntheticAccount", "work"),
                String.join("/", "", "home", "synthetic", "work"),
                String.join("/", "", "Users", "synthetic", "work"))) {
            var check = check();
            check.scanText("example.txt", path);
            assertTrue(has(check, "personal-home-path"));
            assertFalse(check.findings().toString().contains(path));
        }
    }

    @Test void acceptsRuntimeResolvedPathsAndTechnicalIdentifiers() {
        var check = check();
        check.scanText("example.java", "Path.of(System.getProperty(\"user.home\"), \".tropimon\"); fr.tropimon.damagecalc");
        assertTrue(check.findings().isEmpty());
    }

    @Test void detectsSuppliedPrivateTermsWithoutVersionedRealValues() {
        var check = new DistributionPrivacyCheck(List.of("Synthetic Person", "Synthetic Employer"), Map.of());
        check.scanText("profile.txt", "Author Synthetic Person; work Synthetic Employer");
        assertTrue(has(check, "private-term"));
        assertFalse(check.findings().toString().contains("Synthetic Person"));
        assertFalse(check.findings().toString().contains("Synthetic Employer"));
    }

    @Test void detectsEmailAndTokenFixturesAndRedactsTheirNames() {
        String email = String.join("@", "synthetic.person", "example.invalid");
        String token = String.join("", "gh", "p_", "x".repeat(30));
        var check = check();
        check.scanText(email + ".txt", email + " " + token);
        assertTrue(has(check, "email-needs-review"));
        assertTrue(has(check, "credential-token"));
        assertFalse(check.findings().toString().contains(email));
        assertFalse(check.findings().toString().contains(token));
    }

    @Test void checksNestedArchivesAndPrivateEntryNames() throws Exception {
        String token = String.join("", "github", "_pat_", "y".repeat(30));
        var check = check();
        check.scan("release.jar", zip("META-INF/jars/nested.jar", zip(".env", bytes(token))), 0);
        assertTrue(has(check, "credential-token"));
        assertTrue(has(check, "excluded-private-content"));
    }

    @Test void rejectsLogsBackupsSessionsAndPrivateScreenshots() throws Exception {
        for (String entry : List.of("logs/latest.log", "screenshots/capture.png", "backups/data.json",
                ".git/config", ".env.local", "config/session.json", "options.txt", "accounts.json")) {
            var check = check();
            check.scan("release.jar", zip(entry, bytes("{}")), 0);
            assertTrue(has(check, "excluded-private-content"));
        }
    }

    @Test void rejectsUnreviewedImagesInsteadOfAssumingPixelsAreAnonymous() throws Exception {
        var check = check();
        check.scan("assets/tropimon_damage_calc/textures/new.png", new byte[] {1, 2, 3}, 0);
        assertTrue(has(check, "unreviewed-image"));
    }

    @Test void readsCompiledConstantPoolIncludingPersonalPathConstants() throws Exception {
        String path = String.join("/", "C:", "Users", "SyntheticAccount", "project");
        Path source = temp.resolve("Fixture.java");
        Files.writeString(source, "public class Fixture { public static final String HOME = \"" + path + "\"; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", temp.toString(), source.toString()));
        var check = check();
        check.scan("Fixture.class", Files.readAllBytes(temp.resolve("Fixture.class")), 0);
        assertTrue(has(check, "personal-home-path"));
        assertTrue(check.findings().toString().contains("constant["));
    }

    @Test void invalidOrOversizedArchivesCannotSilentlyPass() {
        var check = check();
        assertThrows(Exception.class, () -> check.scan("huge.jar", new byte[17 * 1024 * 1024], 0));
        assertThrows(Exception.class, () -> check.scan("bad.class", new byte[] {0}, 0));
    }

    @Test void jsonEscapesAndUtf16CannotHideResourceStrings() throws Exception {
        String email = String.join("@", "synthetic.person", "example.invalid");
        var check = check();
        String escaped = email.chars().mapToObj(value -> "\\u" + String.format("%04x", value))
                .collect(java.util.stream.Collectors.joining());
        check.scan("credits.json", bytes("{\"contact\":\"" + escaped + "\"}"), 0);
        assertTrue(has(check, "email-needs-review"));
        var utf16 = check();
        utf16.scan("credits.txt", email.getBytes(StandardCharsets.UTF_16), 0);
        assertTrue(has(utf16, "email-needs-review"));
    }

    @Test void commandLineGateRejectsANewFileAndDoesNotEchoItsEmail() throws Exception {
        prepareProject();
        assertEquals(0, runCheck("sources").code());
        String email = String.join("@", "synthetic.new.file", "example.invalid");
        Files.createDirectories(temp.resolve("docs"));
        Files.writeString(temp.resolve("docs/new.md"), email);
        var result = runCheck("sources");
        assertNotEquals(0, result.code());
        assertTrue(result.output().contains("email-needs-review"));
        assertFalse(result.output().contains(email));
    }

    @Test void projectArchiveExcludesLocalLogsWithoutDeletingThem() throws Exception {
        prepareProject();
        Files.createDirectories(temp.resolve("logs"));
        Path log = temp.resolve("logs/session.log");
        Files.writeString(log, "Synthetic private session");
        Path archive = temp.resolve("build/distributions/project.zip");
        assertEquals(0, runCheck("source-zip", archive.toString()).code());
        assertTrue(Files.exists(log));
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            assertNull(zip.getEntry("logs/session.log"));
            assertNotNull(zip.getEntry("src/main/resources/fabric.mod.json"));
        }
        assertEquals(0, runCheck("archives", archive.toString()).code());
    }

    private void prepareProject() throws Exception {
        Files.createDirectories(temp.resolve("tools/privacy"));
        Files.writeString(temp.resolve("tools/privacy/reviewed-assets.json"), "{}");
        Files.createDirectories(temp.resolve("src/main/resources"));
        Files.write(temp.resolve("src/main/resources/fabric.mod.json"), metadata("By FastedCorsi"));
    }

    private RunResult runCheck(String... args) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = String.join(java.io.File.pathSeparator,
                Path.of(DistributionPrivacyCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var command = new java.util.ArrayList<>(List.of(javaExecutable, "-cp", classpath, DistributionPrivacyCheck.class.getName(), temp.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
        return new RunResult(process.exitValue(), output);
    }

    private record RunResult(int code, String output) { }

    private static DistributionPrivacyCheck check() { return new DistributionPrivacyCheck(List.of(), Map.of()); }
    private static boolean has(DistributionPrivacyCheck check, String category) {
        return check.findings().stream().anyMatch(finding -> finding.endsWith(": " + category));
    }
    private static byte[] metadata(String author) {
        return bytes("{\"id\":\"tropimon_damage_calc\",\"authors\":[\"" + author + "\"]}");
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] zip(String name, byte[] data) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(data);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}

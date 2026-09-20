package fr.tropimon.damagecalc;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdaterSafetyTest {
    @TempDir Path temp;
    private static final String ID = "example_mod";
    private void jar(Path path, String id, String version) throws Exception {
        Files.createDirectories(path.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(("{\"id\":\"" + id + "\",\"version\":\"" + version + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
    private Properties prepare(boolean managed) throws Exception {
        Path target = temp.resolve("profile/instance/mods/example [literal].jar"), staged = temp.resolve("updates/new.jar");
        jar(target, ID, "1.0.0"); jar(staged, ID, "1.0.1");
        jar(target.resolveSibling("unrelated.jar"), "unrelated", "3.0.0");
        if (managed) {
            Path copy = temp.resolve("profile/instance/mods-user/example [literal].jar");
            Files.createDirectories(copy.getParent()); Files.copy(target, copy);
            Files.writeString(temp.resolve("profile/user-mods-tracked.json"), "[\"example [literal].jar\",\"other.jar.disabled\"]");
        }
        return TropimonUpdateInstaller.prepare(target, staged, ID, "1.0.1");
    }
    private void unchanged(Properties p) throws Exception {
        assertEquals(p.getProperty("oldHash"), TropimonUpdateInstaller.hash(Path.of(p.getProperty("target"))));
    }
    @Test void standardLauncherNeedsNoChangesAndKeepsBackup() throws Exception {
        Properties p = prepare(false); Path target = Path.of(p.getProperty("target")), other = target.resolveSibling("unrelated.jar");
        String unrelated = TropimonUpdateInstaller.hash(other);
        TropimonUpdateInstaller.install(p);
        assertEquals(p.getProperty("newHash"), TropimonUpdateInstaller.hash(target));
        assertEquals(unrelated, TropimonUpdateInstaller.hash(other));
        assertFalse(Files.exists(temp.resolve("profile/instance/mods-user")));
        try (var files = Files.walk(temp.resolve("profile/instance/mod-archive"))) {
            Path backup = files.filter(f -> f.getFileName().toString().equals("before-0.jar")).findFirst().orElseThrow();
            assertEquals(p.getProperty("oldHash"), TropimonUpdateInstaller.hash(backup));
        }
    }
    @Test void managedLauncherGetsBothCopiesWithoutChangingTrackerOrFilename() throws Exception {
        Properties p = prepare(true); String tracker = Files.readString(Path.of(p.getProperty("tracker")));
        TropimonUpdateInstaller.install(p);
        for (String key : List.of("target", "managed")) assertEquals(p.getProperty("newHash"), TropimonUpdateInstaller.hash(Path.of(p.getProperty(key))));
        assertEquals(tracker, Files.readString(Path.of(p.getProperty("tracker"))));
    }
    @Test void modifiedTargetIsNeverOverwritten() throws Exception {
        Properties p = prepare(false); Path target = Path.of(p.getProperty("target"));
        jar(target, ID, "1.0.2"); String hash = TropimonUpdateInstaller.hash(target);
        assertThrows(Exception.class, () -> TropimonUpdateInstaller.install(p));
        assertEquals(hash, TropimonUpdateInstaller.hash(target));
    }
    @Test void alteredDownloadIsRejected() throws Exception {
        Properties p = prepare(false); Files.writeString(Path.of(p.getProperty("staged")), "altered");
        assertThrows(Exception.class, () -> TropimonUpdateInstaller.install(p)); unchanged(p);
    }
    @Test void changedManagedCopyAndTrackerArePreserved() throws Exception {
        Properties p = prepare(true); Path copy = Path.of(p.getProperty("managed"));
        jar(copy, ID, "1.0.2"); assertThrows(Exception.class, () -> TropimonUpdateInstaller.install(p));
        Files.copy(Path.of(p.getProperty("target")), copy, StandardCopyOption.REPLACE_EXISTING);
        Path tracker = Path.of(p.getProperty("tracker")); Files.writeString(tracker, "[]");
        assertThrows(Exception.class, () -> TropimonUpdateInstaller.install(p));
        assertEquals("[]", Files.readString(tracker)); unchanged(p);
    }
    @Test void duplicateModIsRejected() throws Exception {
        Properties p = prepare(false); jar(Path.of(p.getProperty("target")).resolveSibling("duplicate.jar"), ID, "0.9.0");
        assertThrows(Exception.class, () -> TropimonUpdateInstaller.install(p)); unchanged(p);
    }
    @Test void wrongIdAndDowngradeAreRejected() throws Exception {
        Properties p = prepare(false); Path staged = Path.of(p.getProperty("staged")), target = Path.of(p.getProperty("target"));
        jar(staged, "other", "1.0.1"); assertThrows(Exception.class, () -> TropimonUpdateInstaller.prepare(target, staged, ID, "1.0.1"));
        jar(staged, ID, "0.9.0"); assertThrows(Exception.class, () -> TropimonUpdateInstaller.prepare(target, staged, ID, "0.9.0"));
    }
    @Test void lockedTargetIsPreserved() throws Exception {
        Properties p = prepare(false);
        try (var channel = FileChannel.open(Path.of(p.getProperty("target")), StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(Exception.class, () -> TropimonUpdateInstaller.install(p));
        }
        unchanged(p);
    }
    @Test void jarInUseByAReaderIsNotReplacedOnWindows() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Properties p = prepare(false);
        try (var reader = new java.util.zip.ZipFile(Path.of(p.getProperty("target")).toFile())) {
            assertNotNull(reader.getEntry("fabric.mod.json"));
            assertThrows(Exception.class, () -> TropimonUpdateInstaller.install(p));
        }
        unchanged(p);
    }
    @Test void unknownOrChangedLauncherLayoutIsRejected() throws Exception {
        Properties p = prepare(false); Files.createDirectory(temp.resolve("profile/instance/mods-user"));
        assertThrows(Exception.class, () -> TropimonUpdateInstaller.install(p)); unchanged(p);
    }
    @Test void legacyEnabledFlagDoesNotGrantConsent() {
        for (String config : List.of("{}", "{\"enabled\":true}", "{\"consentVersion\":1,\"allowChecks\":true}", "{\"consentVersion\":2,\"allowChecks\":false}"))
            assertFalse(TropimonSelfUpdater.checksConsented(JsonParser.parseString(config).getAsJsonObject()));
        assertTrue(TropimonSelfUpdater.checksConsented(JsonParser.parseString("{\"consentVersion\":2,\"allowChecks\":true}").getAsJsonObject()));
    }
    @Test void downloadRequiresExactOfferAndSingleExplicitApproval() {
        var offer = new TropimonSelfUpdater.ReleaseOffer("1.0.1", null, null, null);
        var other = new TropimonSelfUpdater.ReleaseOffer("1.0.2", null, null, null);
        assertFalse(TropimonSelfUpdater.consumeApproval(null, null, true));
        assertFalse(TropimonSelfUpdater.consumeApproval(offer, offer, false));
        assertFalse(TropimonSelfUpdater.consumeApproval(offer, other, true));
        assertTrue(TropimonSelfUpdater.consumeApproval(offer, offer, true));
        assertFalse(TropimonSelfUpdater.consumeApproval(offer, offer, true));
    }
    @Test void onlyStableConsentReleasesAreOffered() {
        var releases = JsonParser.parseString("""
            [
              {"tag_name":"v9.0.0","draft":false,"prerelease":false,"body":"legacy"},
              {"tag_name":"v5.0.0","draft":false,"prerelease":true,"body":"<!-- tropimon-consent-updater:2 -->"},
              {"tag_name":"v4.0.0","draft":true,"prerelease":false,"body":"<!-- tropimon-consent-updater:2 -->"},
              {"tag_name":"v1.0.1","draft":false,"prerelease":false,"body":"<!-- tropimon-consent-updater:2 -->"}
            ]
            """).getAsJsonArray();
        assertEquals("v1.0.1", TropimonSelfUpdater.selectRelease(releases).get("tag_name").getAsString());
        releases.remove(3); assertNull(TropimonSelfUpdater.selectRelease(releases));
    }
    @Test void standaloneInstallerRunsUsingOnlyJavaAndExistingJsonLibrary() throws Exception {
        Properties p = prepare(false); p.setProperty("parentPid", "2147483647"); p.setProperty("parentStarted", Instant.EPOCH.toString());
        Process process = TropimonSelfUpdater.launchInstaller(temp, p);
        awaitTestProcess(process);
        assertEquals(0, process.exitValue(), Files.readString(temp.resolve("install.log")));
        assertEquals("installed\n", Files.readString(temp.resolve("status.txt")));
        assertEquals(p.getProperty("newHash"), TropimonUpdateInstaller.hash(Path.of(p.getProperty("target"))));
    }

    @Test void actualChildWaitsForParentBeforeInstalling() throws Exception {
        Properties p = prepare(false);
        String cp = Path.of(UpdaterSafetyTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process parent = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", cp, WaitingChild.class.getName()).redirectErrorStream(true).redirectOutput(temp.resolve("parent.log").toFile()).start();
        p.setProperty("parentPid", Long.toString(parent.pid()));
        p.setProperty("parentStarted", parent.info().startInstant().orElseThrow().toString());
        Process installer = TropimonSelfUpdater.launchInstaller(temp, p);
        Thread.sleep(500);
        assertTrue(parent.isAlive()); assertTrue(installer.isAlive()); unchanged(p);
        assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
        awaitTestProcess(installer);
        assertEquals(0, installer.exitValue(), Files.readString(temp.resolve("install.log")));
        assertEquals(p.getProperty("newHash"), TropimonUpdateInstaller.hash(Path.of(p.getProperty("target"))));
    }

    private static void awaitTestProcess(Process process) throws Exception {
        try {
            // Three bounded OS process queries plus JVM startup can exceed 20 seconds under load.
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Test-owned installer timed out");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly(); // Only the synthetic helper created by this test.
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    public static class WaitingChild {
        public static void main(String[] args) throws Exception { Thread.sleep(3000); }
    }

    @Test void cimRechecksExitedProcessesWithoutIgnoringUnreadableLiveOnes() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path instance = temp.resolve("instance with spaces");
        String readable = "[pscustomobject]@{ProcessId=2147483646;CommandLine='java --gameDir \""
                + instance.toString().replace("'", "''") + "\"'}";
        for (String fresh : List.of("return", "[pscustomobject]@{ProcessId=2147483646;CommandLine=$null}", readable, "throw 'Query unavailable'")) {
            String mock = "function Get-CimInstance { param($ClassName,$Filter) "
                    + "if ($Filter -eq 'ProcessId=2147483646') { " + fresh + "; return }; "
                    + "[pscustomobject]@{ProcessId=2147483646;CommandLine=$null} }; ";
            String encoded = Base64.getEncoder().encodeToString((mock + TropimonUpdateInstaller.windowsProcessQuery())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            Path executable = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            Path output = temp.resolve("query.txt");
            Process process = new ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile()).start();
            awaitTestProcess(process);
            String json = Files.readString(output).strip();
            if (fresh.startsWith("throw")) assertNotEquals(0, process.exitValue(), "CIM failures must block installation");
            else {
                assertEquals(0, process.exitValue());
                if (fresh.equals("return")) assertFalse(TropimonUpdateInstaller.windowsSnapshotRunning(json, instance));
                else if (fresh.equals(readable)) assertTrue(TropimonUpdateInstaller.windowsSnapshotRunning(json, instance));
                else assertThrows(IOException.class, () -> TropimonUpdateInstaller.windowsSnapshotRunning(json, instance));
            }
        }
        assertThrows(IOException.class, () -> TropimonUpdateInstaller.windowsSnapshotRunning(
                "[{\"ProcessId\":2147483646,\"CommandLine\":\" \"}]", instance));
    }

    @Test void quotedGameDirArgumentsAndUnrelatedJavaProcessesAreRecognized() throws Exception {
        Path instance = temp.resolve("instance with spaces");
        assertTrue(TropimonUpdateInstaller.sameInstance("java \"--gameDir\" \"" + instance + "\"", instance));
        assertTrue(TropimonUpdateInstaller.sameInstance("java --gameDir=\"" + instance + "\"", instance));
        assertFalse(TropimonUpdateInstaller.sameInstance("java --gameDir \"" + temp.resolve("other") + "\"", instance));
        assertFalse(TropimonUpdateInstaller.sameInstance("java -Dexample=net.minecraft.client.toast.SystemToast Worker", instance));
        assertThrows(IOException.class, () -> TropimonUpdateInstaller.sameInstance("java net.minecraft.client.main.Main", instance));
    }
}

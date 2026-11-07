package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sonar-gate atom, started the way the runner starts it, against the fixture in {@code tools/fake_sonar.py}.
 *
 * <p>The checks the old {@code tools/smoke.sh} made: a green gate, the branch parameter, a red gate that names its
 * failing conditions, the WARN policy, 401/404, a missing input, and the compute-engine wait — queue observed, stale
 * gate not believed, timeout, failed analysis, the {@code componentKey} spelling of SonarQube 8.
 */
class SonarGateIT {

    private Atom atom;
    private final List<Process> servers = new ArrayList<>();
    private String base;
    private Path log;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @AfterEach
    void tearDown() {
        servers.forEach(Process::destroy);
        servers.clear();
    }

    /** One fake SonarQube per scenario; the smoke restarted it the same way between halves. */
    private void serve(String... flags) throws Exception {
        servers.forEach(Process::destroy);
        servers.clear();
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        log = Path.of(System.getProperty("atom.scratch")).resolve("fake-sonar-" + port + ".log");
        Files.deleteIfExists(log);
        List<String> command = new ArrayList<>(List.of("python3",
                Path.of(System.getProperty("atom.module")).resolve("tools/fake_sonar.py").toString(),
                String.valueOf(port), log.toString()));
        command.addAll(List.of(flags));
        Process server = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        Path.of(System.getProperty("atom.scratch")).resolve("fake-sonar-" + port + ".out").toFile()))
                .start();
        servers.add(server);
        for (int attempt = 0; attempt < 100; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                base = "http://127.0.0.1:" + port;
                return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("the fake SonarQube did not start");
    }

    /** A run with the shared connection fields, the scenario's own extras, and a token secret. */
    private AtomResult gate(Atom a, String extras, String token) throws IOException, InterruptedException {
        return a.file(".secrets.json", "{\"token\": \"" + token + "\"}")
                .input("{\"sonarUrl\": \"" + base + "\", \"projectKey\": \"acme:api\"" + extras + "}")
                .run();
    }

    private AtomResult gate(Atom a, String extras) throws IOException, InterruptedException {
        return gate(a, extras, "t");
    }

    private AtomResult gate(String extras) throws Exception {
        return gate(atom, extras, "t");
    }

    private List<Map<String, Object>> requests() throws IOException {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(Json.parseObject(line));
            }
        }
        return records;
    }

    /** The {@code data} map, for the reserved build-result key that holds a dot. */
    private static String reserved(AtomResult run, String key) {
        Object data = run.document().get("data");
        if (data instanceof Map<?, ?> map && map.get(key) instanceof Map<?, ?> node) {
            return String.valueOf(node.get("value"));
        }
        return "";
    }

    @Test
    @DisplayName("a green gate passes, asks the right endpoint, and sends the token as Basic auth")
    void passesAGreenGate() throws Exception {
        serve("--gate", "OK");
        AtomResult run = gate(atom, "", "sqa_123");

        run.assertOk();
        assertEquals("OK", run.field("gateStatus"));
        Map<String, Object> request = requests().get(0);
        assertEquals("/api/qualitygates/project_status?projectKey=acme%3Aapi", request.get("path"));
        assertEquals("Basic c3FhXzEyMzo=", request.get("authorization"), "token auth header");
    }

    @Test
    @DisplayName("the branch is passed through to the gate call")
    void passesTheBranch() throws Exception {
        serve("--gate", "OK");
        AtomResult run = gate(atom, ", \"branch\": \"release/1.0\"");

        run.assertOk();
        assertTrue(requests().stream().anyMatch(record -> String.valueOf(record.get("path"))
                .contains("branch=release%2F1.0")), "branch not passed: " + requests());
    }

    @Test
    @DisplayName("a red gate fails and names both failing conditions, not the one that is fine")
    void namesTheFailingConditions() throws Exception {
        serve("--gate", "ERROR");
        AtomResult run = gate(atom, "");

        assertEquals("error", run.status(), "a red gate must fail the step");
        String output = run.file("output.json");
        assertTrue(output.contains("new_coverage is 71.4 (required"), "coverage condition missing: " + output);
        assertTrue(output.contains("new_bugs is 3 (required "), "bugs condition missing: " + output);
        assertFalse(output.contains("new_lines"), "a passing condition must not be listed: " + output);
        assertEquals("2", run.field("failingConditions"));
    }

    @Test
    @DisplayName("WARN passes by default and fails only when failOnWarning is set")
    void appliesTheWarningPolicy() throws Exception {
        serve("--gate", "WARN");
        gate(atom, "").assertOk();

        AtomResult strict = gate(atom, ", \"failOnWarning\": \"true\"");
        assertEquals("error", strict.status(), "WARN must fail when failOnWarning is set");
    }

    @Test
    @DisplayName("a refused token is a user error and says so")
    void reportsA401() throws Exception {
        serve("--gate", "OK", "--status", "401");
        AtomResult run = gate(atom, "", "bad");

        assertEquals("error", run.status(), "401 must fail");
        assertTrue(run.file("output.json").contains("refused the token"), run.file("output.json"));
        assertEquals("1", run.field("errorType"), "401 should be USER(1)");
    }

    @Test
    @DisplayName("a project without a quality gate says so")
    void reportsA404() throws Exception {
        serve("--gate", "OK", "--status", "404");
        AtomResult run = gate(atom, "");

        assertEquals("error", run.status());
        assertTrue(run.file("output.json").contains("no quality gate found for project"),
                run.file("output.json"));
    }

    @Test
    @DisplayName("a missing sonarUrl is refused by name")
    void refusesWithoutASonarUrl() throws Exception {
        AtomResult run = atom.input("{\"projectKey\": \"x\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("sonarUrl is required"), run.file("output.json"));
    }

    @Test
    @DisplayName("waiting for the analysis turns a stale red gate into the fresh green one")
    void waitsForTheAnalysis() throws Exception {
        serve("--gate", "OK", "--stale-gate", "ERROR", "--ce-steps", "2");
        AtomResult run = gate(atom, ", \"waitForAnalysis\": \"true\", \"pollIntervalSeconds\": \"1\","
                + " \"waitTimeoutSeconds\": \"30\"");

        run.assertOk();
        assertEquals("OK", run.field("gateStatus"), "waiting must reach the fresh gate");
        assertEquals("SUCCESS", run.field("ceTaskStatus"));

        List<Map<String, Object>> gates = requests().stream()
                .filter(record -> "gate".equals(record.get("kind"))).toList();
        assertTrue(!gates.isEmpty(), "no gate request at all");
        assertTrue(gates.stream().allMatch(record -> Boolean.TRUE.equals(record.get("fresh"))),
                "read a stale gate: " + gates);
        assertTrue(run.console().contains("1 analysis task(s) still queued"),
                "the queue was never observed: " + run.console());
    }

    @Test
    @DisplayName("without waiting the stale gate is served and believed — the bug the wait fixes")
    void believesTheStaleGateWithoutWaiting() throws Exception {
        serve("--gate", "OK", "--stale-gate", "ERROR", "--ce-steps", "2");
        AtomResult run = gate(atom, "");

        assertEquals("error", run.status(), "without waiting the stale gate must be read (it is ERROR)");
        assertTrue(run.file("output.json").contains("new_coverage is 71.4"),
                "stale gate conditions: " + run.file("output.json"));
    }

    @Test
    @DisplayName("an analysis that never finishes times out as a third-party problem")
    void timesOutOnAQueueThatNeverDrains() throws Exception {
        serve("--gate", "OK", "--ce", "never");
        AtomResult run = gate(atom, ", \"waitForAnalysis\": \"true\", \"pollIntervalSeconds\": \"1\","
                + " \"waitTimeoutSeconds\": \"5\"");

        assertEquals("error", run.status(), "a queue that never drains must time out");
        assertTrue(run.file("output.json").contains("did not finish within 5s"), run.file("output.json"));
        assertEquals("2", run.field("errorType"), "timeout should be THIRD_PART(2)");
    }

    @Test
    @DisplayName("a failed analysis is reported at once instead of waiting out the timeout")
    void reportsAFailedAnalysis() throws Exception {
        serve("--gate", "OK", "--ce", "failed");
        AtomResult run = gate(atom, ", \"waitForAnalysis\": \"true\", \"pollIntervalSeconds\": \"1\","
                + " \"waitTimeoutSeconds\": \"10\"");

        assertEquals("error", run.status(), "a failed analysis must not produce a gate");
        assertTrue(run.file("output.json").contains("ended as FAILED"), run.file("output.json"));
        assertFalse(requests().stream().anyMatch(record -> "SUCCESS".equals(record.get("served"))
                && "ce".equals(record.get("kind"))), "a failed analysis must never be reported as SUCCESS");
    }

    @Test
    @DisplayName("SonarQube 8's componentKey spelling is retried after the first 400, not failed")
    void retriesTheComponentKeySpelling() throws Exception {
        serve("--gate", "OK", "--param", "componentKey", "--ce-steps", "1");
        AtomResult run = gate(atom, ", \"waitForAnalysis\": \"true\", \"pollIntervalSeconds\": \"1\","
                + " \"waitTimeoutSeconds\": \"20\"");

        run.assertOk();
        assertTrue(requests().stream().anyMatch(record -> "400-wrong-param".equals(record.get("served"))),
                "the fake never rejected 'component=': " + requests());
        assertTrue(requests().stream().anyMatch(record -> String.valueOf(record.get("path"))
                .contains("componentKey=acme%3Aapi")), "no retry with componentKey=");
    }

    @Test
    @DisplayName("buildStatus=UNSTABLE keeps the step green and still reports the gate and the reason")
    void mapsTheBuildStatus() throws Exception {
        serve("--gate", "ERROR", "--ce-steps", "0");
        AtomResult run = gate(atom, ", \"buildStatus\": \"UNSTABLE\"");

        run.assertOk();
        assertEquals("UNSTABLE", reserved(run, "build.result"), "build.result");
        assertEquals("false", run.field("passed"));
        assertEquals("ERROR", run.field("gateStatus"));
        assertTrue(run.file("output.json").contains("the quality gate is ERROR"),
                "the reason must still be in the message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("FAILURE is the default and an unknown buildStatus is refused")
    void validatesTheBuildStatus() throws Exception {
        serve("--gate", "ERROR", "--ce-steps", "0");
        assertEquals("error", gate(atom, "").status(), "FAILURE is the default");

        AtomResult unknown = gate(atom, ", \"buildStatus\": \"SOMETIMES\"");
        assertTrue(unknown.file("output.json").contains("buildStatus must be FAILURE or UNSTABLE"),
                "unknown buildStatus: " + unknown.file("output.json"));
    }

    @Test
    @DisplayName("the fake is not just saying yes: swapping the gate changes the verdict")
    void reachesAGreenGateAfterWaiting() throws Exception {
        serve("--gate", "OK", "--stale-gate", "OK");
        AtomResult run = gate(atom, ", \"waitForAnalysis\": \"true\", \"pollIntervalSeconds\": \"1\","
                + " \"waitTimeoutSeconds\": \"10\"");

        run.assertOk();
        assertEquals("OK", run.field("gateStatus"));
    }
}

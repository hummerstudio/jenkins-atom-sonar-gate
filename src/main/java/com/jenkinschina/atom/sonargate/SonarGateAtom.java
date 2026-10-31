package com.jenkinschina.atom.sonargate;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read SonarQube's quality gate for a project, and fail (or mark unstable) when it is red.
 *
 * <p>The scan itself is {@code sonar-scan}; this is the other half of the family — the gate. Three behaviours matter:
 * a gate that failed must be reflected in the build (otherwise "analysis published" reads as "quality is fine"), the
 * <em>failing conditions</em> must be named in the message (because "quality gate failed" alone sends people to the UI),
 * and the gate must be the one produced by <em>this</em> build's analysis — hence the optional wait for the compute
 * engine task, which is what makes the read safe right after a scan.
 *
 * <p>Port of the "Sonar Quality Gates" plugin (org.quality.gates): its {@code QGPublisher} waited on
 * {@code /api/ce/component} until the queue was empty and the current task was {@code SUCCESS}, then read
 * {@code /api/qualitygates/project_status} and did {@code setResult(UNSTABLE)} or failed the build. This atom keeps that
 * order; it does not keep the plugin's habit of swallowing exceptions (a JSON parse error there printed a stack trace
 * and left the build green).
 */
public class SonarGateAtom implements AtomTask {

    private static final int DEFAULT_WAIT_SECONDS = 300;
    private static final int DEFAULT_POLL_SECONDS = 10;

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String url = context.getParam("sonarUrl", "").trim();
        String token = context.getSecret("token");
        if (token == null) {
            token = context.getParam("token", "");
        }
        String projectKey = context.getParam("projectKey", "").trim();
        String branch = context.getParam("branch", "").trim();
        String pullRequest = context.getParam("pullRequest", "").trim();
        boolean failOnWarning = context.getBooleanParam("failOnWarning", false);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 60), 600));

        boolean waitForAnalysis = context.getBooleanParam("waitForAnalysis", false);
        int waitTimeoutSeconds = Math.max(5, Math.min(
                context.getIntParam("waitTimeoutSeconds", DEFAULT_WAIT_SECONDS), 3600));
        int pollIntervalSeconds = Math.max(1, Math.min(
                context.getIntParam("pollIntervalSeconds", DEFAULT_POLL_SECONDS), 60));
        String buildStatus = context.getParam("buildStatus", "FAILURE").trim().toUpperCase(Locale.ROOT);
        if (buildStatus.isEmpty()) {
            buildStatus = "FAILURE";
        }
        String componentKey = context.getParam("componentKey", "").trim();

        if (url.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "sonarUrl is required");
        }
        if (projectKey.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "projectKey is required");
        }
        if (!buildStatus.equals("FAILURE") && !buildStatus.equals("UNSTABLE")) {
            return AtomResult.error(ErrorType.USER, 40003,
                    "buildStatus must be FAILURE or UNSTABLE, not '" + buildStatus + "'");
        }
        if (componentKey.isEmpty()) {
            componentKey = projectKey;
        }

        String base = url.replaceAll("/+$", "");
        String header = (token == null || token.isBlank()) ? null : basicAuth(token);
        String ceStatus = "";

        if (waitForAnalysis) {
            CeOutcome outcome = awaitAnalysis(context, base, header, componentKey,
                    waitTimeoutSeconds, pollIntervalSeconds, timeoutSeconds);
            if (outcome.error != null) {
                return outcome.error;
            }
            ceStatus = outcome.status;
            context.log("[sonar-gate] analysis of " + componentKey + " finished: " + ceStatus
                    + " after " + outcome.polls + " poll(s), " + outcome.waitedMillis + " ms");
        }

        String endpoint = base + "/api/qualitygates/project_status?projectKey="
                + encode(projectKey)
                + (branch.isEmpty() ? "" : "&branch=" + encode(branch))
                + (pullRequest.isEmpty() ? "" : "&pullRequest=" + encode(pullRequest));

        HttpResponse<String> response;
        try {
            response = get(endpoint, header, timeoutSeconds);
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40004,
                    "could not reach SonarQube within " + timeoutSeconds + "s: " + endpoint);
        } catch (java.io.IOException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40004,
                    "querying SonarQube failed: " + e.getMessage());
        }
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status == 401 || status == 403) {
            return AtomResult.error(ErrorType.USER, 40005,
                    "SonarQube refused the token (HTTP " + status + "); check the token's permission on " + projectKey);
        }
        if (status == 404) {
            return AtomResult.error(ErrorType.USER, 40006,
                    "no quality gate found for project '" + projectKey + "' (HTTP 404) — is the project key right, "
                            + "and has the scan ever run?");
        }
        if (status < 200 || status >= 300) {
            return AtomResult.error(ErrorType.THIRD_PART, 40007,
                    "SonarQube answered HTTP " + status + ": " + excerpt(body));
        }

        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (RuntimeException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40008, "SonarQube did not answer JSON: " + excerpt(body));
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("projectStatus") instanceof Map<?, ?> gate)) {
            return AtomResult.error(ErrorType.THIRD_PART, 40009, "the response has no projectStatus: " + excerpt(body));
        }

        String gateStatus = String.valueOf(gate.get("status"));
        List<String> failing = new ArrayList<>();
        Object conditions = gate.get("conditions");
        if (conditions instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> condition)) {
                    continue;
                }
                String conditionStatus = String.valueOf(condition.get("status"));
                if ("ERROR".equals(conditionStatus) || ("WARN".equals(conditionStatus) && failOnWarning)) {
                    failing.add(describe(condition));
                }
            }
        }

        context.log("[sonar-gate] " + projectKey + (branch.isEmpty() ? "" : " (" + branch + ")")
                + " gate=" + gateStatus
                + (failing.isEmpty() ? "" : ", failing: " + String.join("; ", failing)));

        boolean red = "ERROR".equals(gateStatus) || ("WARN".equals(gateStatus) && failOnWarning);
        if (red) {
            String message = "the quality gate is " + gateStatus
                    + (failing.isEmpty() ? "" : ": " + String.join("; ", failing));
            if ("UNSTABLE".equals(buildStatus)) {
                // The plugin's setResult(UNSTABLE): the step succeeds, the build carries the warning.
                context.log("[sonar-gate] buildStatus=UNSTABLE, so the step continues; the build is marked unstable");
                return AtomResult.success()
                        .data("gateStatus", gateStatus)
                        .data("passed", "false")
                        .data("failingConditions", String.valueOf(failing.size()))
                        .data("conditions", String.join("\n", failing))
                        .data("buildStatus", buildStatus)
                        .data("message", message)
                        .data("ceTaskStatus", ceStatus)
                        .markUnstable();
            }
            return AtomResult.error(ErrorType.USER, 40010, message)
                    .data("gateStatus", gateStatus)
                    .data("passed", "false")
                    .data("failingConditions", String.valueOf(failing.size()))
                    .data("conditions", String.join("\n", failing))
                    .data("buildStatus", buildStatus)
                    .data("ceTaskStatus", ceStatus);
        }
        return AtomResult.success()
                .data("gateStatus", gateStatus)
                .data("passed", "true")
                .data("failingConditions", String.valueOf(failing.size()))
                .data("conditions", String.join("\n", failing))
                .data("buildStatus", buildStatus)
                .data("ceTaskStatus", ceStatus);
    }

    /** What the compute-engine wait ended with: a status to log, or the error to return as-is. */
    private static final class CeOutcome {
        final String status;
        final AtomResult error;
        final int polls;
        final long waitedMillis;

        CeOutcome(String status, AtomResult error, int polls, long waitedMillis) {
            this.status = status;
            this.error = error;
            this.polls = polls;
            this.waitedMillis = waitedMillis;
        }
    }

    /**
     * Wait until the analysis queue for {@code componentKey} is empty and the current task is SUCCESS.
     *
     * <p>SonarQube 8.0 spelled the parameter {@code componentKey}; 8.8+ spells it {@code component} and rejects the old
     * name with HTTP 400, so the first 400 switches spelling instead of failing.
     */
    private CeOutcome awaitAnalysis(AtomContext context, String base, String header, String componentKey,
            int waitSeconds, int pollSeconds, int timeoutSeconds) throws InterruptedException {
        long started = System.currentTimeMillis();
        long deadline = started + waitSeconds * 1000L;
        String parameter = "component";
        int polls = 0;
        while (true) {
            polls++;
            String endpoint = base + "/api/ce/component?" + parameter + "=" + encode(componentKey);
            HttpResponse<String> response;
            try {
                response = get(endpoint, header, Math.min(timeoutSeconds, 30));
            } catch (java.net.http.HttpConnectTimeoutException e) {
                return new CeOutcome("", AtomResult.error(ErrorType.ENVIRONMENT, 40011,
                        "could not reach SonarQube while waiting for the analysis: " + endpoint),
                        polls, System.currentTimeMillis() - started);
            } catch (java.io.IOException e) {
                return new CeOutcome("", AtomResult.error(ErrorType.THIRD_PART, 40012,
                        "polling SonarQube for the analysis status failed: " + e.getMessage()),
                        polls, System.currentTimeMillis() - started);
            }
            int http = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (http == 400 && "component".equals(parameter)) {
                context.log("[sonar-gate] this SonarQube does not know 'component=' (HTTP 400); retrying with"
                        + " 'componentKey=' as SonarQube 8.0 spells it");
                parameter = "componentKey";
                continue;
            }
            if (http == 401 || http == 403) {
                return new CeOutcome("", AtomResult.error(ErrorType.USER, 40013,
                        "SonarQube refused the token while waiting for the analysis (HTTP " + http + ")"),
                        polls, System.currentTimeMillis() - started);
            }
            if (http == 404) {
                return new CeOutcome("", AtomResult.error(ErrorType.USER, 40014,
                        "SonarQube has no such component '" + componentKey + "' (HTTP 404) — is the project key right?"),
                        polls, System.currentTimeMillis() - started);
            }
            if (http < 200 || http >= 300) {
                return new CeOutcome("", AtomResult.error(ErrorType.THIRD_PART, 40015,
                        "SonarQube answered HTTP " + http + " for the analysis status: " + excerpt(body)),
                        polls, System.currentTimeMillis() - started);
            }

            Object parsed;
            try {
                parsed = Json.parse(body);
            } catch (RuntimeException e) {
                return new CeOutcome("", AtomResult.error(ErrorType.THIRD_PART, 40016,
                        "the analysis status is not JSON: " + excerpt(body)),
                        polls, System.currentTimeMillis() - started);
            }
            Map<?, ?> root = parsed instanceof Map<?, ?> map ? map : Map.of();
            Object queue = root.get("queue");
            int queued = queue instanceof List<?> list ? list.size() : 0;
            if (queued > 0) {
                context.log("[sonar-gate] " + queued + " analysis task(s) still queued for " + componentKey);
            } else {
                String current = "";
                if (root.get("current") instanceof Map<?, ?> task) {
                    current = String.valueOf(task.get("status"));
                }
                if ("SUCCESS".equals(current)) {
                    return new CeOutcome(current, null, polls, System.currentTimeMillis() - started);
                }
                if ("FAILED".equals(current) || "CANCELED".equals(current) || "CANCELLED".equals(current)) {
                    // The plugin kept polling until the timeout here; saying so at once is more useful than a timeout.
                    return new CeOutcome(current, AtomResult.error(ErrorType.THIRD_PART, 40017,
                            "the analysis of " + componentKey + " ended as " + current
                                    + "; there is no fresh quality gate for this build"),
                            polls, System.currentTimeMillis() - started);
                }
                if (current.isEmpty()) {
                    context.log("[sonar-gate] nothing queued and no current analysis for " + componentKey
                            + "; reading the gate as it stands");
                    return new CeOutcome("", null, polls, System.currentTimeMillis() - started);
                }
                context.log("[sonar-gate] analysis of " + componentKey + " is " + current);
            }

            if (System.currentTimeMillis() >= deadline) {
                return new CeOutcome("", AtomResult.error(ErrorType.THIRD_PART, 40018,
                        "the analysis of " + componentKey + " did not finish within " + waitSeconds
                                + "s; raise waitTimeoutSeconds or stop waiting with waitForAnalysis=false"),
                        polls, System.currentTimeMillis() - started);
            }
            Thread.sleep(pollSeconds * 1000L);
            if (System.currentTimeMillis() >= deadline) {
                return new CeOutcome("", AtomResult.error(ErrorType.THIRD_PART, 40018,
                        "the analysis of " + componentKey + " did not finish within " + waitSeconds
                                + "s; raise waitTimeoutSeconds or stop waiting with waitForAnalysis=false"),
                        polls, System.currentTimeMillis() - started);
            }
        }
    }

    private static HttpResponse<String> get(String endpoint, String authorization, int timeoutSeconds)
            throws java.io.IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", "jenkins-atom-sonar-gate")
                .GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** SonarQube accepts a token as the basic-auth user name with an empty password. */
    private static String basicAuth(String token) {
        return "Basic " + Base64.getEncoder().encodeToString((token + ":").getBytes(StandardCharsets.UTF_8));
    }

    /** "new_coverage is 71.4% (required >= 80%)" reads better than three raw fields. */
    private static String describe(Map<?, ?> condition) {
        String metric = String.valueOf(condition.get("metricKey"));
        String actual = String.valueOf(condition.get("actualValue"));
        String threshold = String.valueOf(condition.get("errorThreshold"));
        String comparator = String.valueOf(condition.get("comparator"));
        // SonarQube: LT means "below this the metric fails" (coverage >= 80), GT means "above this it fails" (bugs <= 0)
        String relation = comparator.contains("LT") ? "\u2265" : "\u2264";
        return metric + " is " + actual + " (required " + relation + " " + threshold + ")";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String excerpt(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "\u2026" : oneLine;
    }
}

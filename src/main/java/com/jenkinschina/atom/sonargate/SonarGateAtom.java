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
import java.util.Map;

/**
 * Read SonarQube's quality gate for a project, and fail the step when it is red.
 *
 * <p>The scan itself is `sonar-scan`; this is the other half of the plugin family — the gate. Two behaviours matter:
 * a gate that failed must fail the build (otherwise "analysis published" reads as "quality is fine"), and the
 * <em>failing conditions</em> must be named in the message, because "quality gate failed" alone sends people to the
 * UI to find out why.
 */
public class SonarGateAtom implements AtomTask {

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

        if (url.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "sonarUrl is required");
        }
        if (projectKey.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "projectKey is required");
        }
        String endpoint = url.replaceAll("/+$", "") + "/api/qualitygates/project_status?projectKey="
                + encode(projectKey)
                + (branch.isEmpty() ? "" : "&branch=" + encode(branch))
                + (pullRequest.isEmpty() ? "" : "&pullRequest=" + encode(pullRequest));

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", "jenkins-atom-sonar-gate")
                .GET();
        if (token != null && !token.isBlank()) {
            // SonarQube accepts a token as the basic-auth user name with an empty password
            request.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString((token + ":").getBytes(StandardCharsets.UTF_8)));
        }

        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40003,
                    "could not reach SonarQube within " + timeoutSeconds + "s: " + endpoint);
        } catch (java.io.IOException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40004, "querying SonarQube failed: " + e.getMessage());
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
            return AtomResult.error(ErrorType.THIRD_PART, 40008,
                    "SonarQube did not answer JSON: " + excerpt(body));
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("projectStatus") instanceof Map<?, ?> gate)) {
            return AtomResult.error(ErrorType.THIRD_PART, 40009,
                    "the response has no projectStatus: " + excerpt(body));
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
        AtomResult result = AtomResult.success()
                .data("gateStatus", gateStatus)
                .data("failingConditions", String.valueOf(failing.size()))
                .data("conditions", String.join("\n", failing));
        if (red) {
            String message = "the quality gate is " + gateStatus
                    + (failing.isEmpty() ? "" : ": " + String.join("; ", failing));
            return AtomResult.error(ErrorType.USER, 40010, message)
                    .data("gateStatus", gateStatus)
                    .data("failingConditions", String.valueOf(failing.size()))
                    .data("conditions", String.join("\n", failing));
        }
        return result.data("passed", "true");
    }

    /** "new_coverage is 71.4% (required ≥ 80%)" reads better than three raw fields. */
    private static String describe(Map<?, ?> condition) {
        String metric = String.valueOf(condition.get("metricKey"));
        String actual = String.valueOf(condition.get("actualValue"));
        String threshold = String.valueOf(condition.get("errorThreshold"));
        String comparator = String.valueOf(condition.get("comparator"));
        // SonarQube: LT means "below this the metric fails" (coverage ≥ 80), GT means "above this it fails" (bugs ≤ 0)
        String relation = comparator.contains("LT") ? "≥" : "≤";
        return metric + " is " + actual + " (required " + relation + " " + threshold + ")";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String excerpt(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}

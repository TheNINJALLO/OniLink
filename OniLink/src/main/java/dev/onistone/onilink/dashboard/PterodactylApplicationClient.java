package dev.onistone.onilink.dashboard;

import org.jose4j.json.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Minimal bounded client for the Pterodactyl Application API resources used by tenant hosting. */
final class PterodactylApplicationClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PAGES = 100;

    private final String panelUrl;
    private final String apiKey;
    private final HttpClient client;

    PterodactylApplicationClient(String panelUrl, String apiKey) {
        this.panelUrl = panelUrl;
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    List<Map<String, Object>> list(String path) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        String separator = path.contains("?") ? "&" : "?";
        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, Object> response = call("GET", path + separator + "per_page=100&page=" + page, null);
            Object rawData = response.get("data");
            if (!(rawData instanceof List<?> data)) {
                throw new IOException("Pterodactyl returned an invalid collection");
            }
            for (Object rawItem : data) result.add(attributes(rawItem));
            Map<String, Object> pagination = nestedMap(response, "meta", "pagination");
            int totalPages = pagination.isEmpty() ? 1 : number(pagination.get("total_pages"));
            if (page >= totalPages) return List.copyOf(result);
        }
        throw new IOException("Pterodactyl collection exceeded the safe pagination limit");
    }

    Optional<Map<String, Object>> findTenantServer(String tenantId) throws IOException {
        String externalId = URLEncoder.encode("onilink-tenant-" + tenantId, StandardCharsets.UTF_8)
                .replace("+", "%20");
        try {
            return Optional.of(item("GET", "/servers/external/" + externalId, null));
        } catch (ApiFailure failure) {
            if (failure.status == 404) return Optional.empty();
            throw failure;
        }
    }

    Map<String, Object> item(String method, String path, Map<String, Object> payload) throws IOException {
        return attributes(call(method, path, payload));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, Map<String, Object> payload) throws IOException {
        if (!path.startsWith("/") || path.contains("..")) throw new IllegalArgumentException("Invalid API path");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(panelUrl + "/api/application" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.pterodactyl.v1+json")
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "OniLink-Control-Plane/1");
        if (payload == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(DashboardJson.encode(payload)));
        }

        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Pterodactyl request was interrupted", exception);
        }

        byte[] bytes;
        try (InputStream input = response.body()) {
            bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Pterodactyl response was too large");
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiFailure(response.statusCode(), errorDetail(body));
        }
        if (body.isBlank()) return Map.of();
        try {
            Object parsed = JsonUtil.parseJson(body);
            if (!(parsed instanceof Map<?, ?> map)) throw new IOException("Pterodactyl returned invalid JSON");
            return (Map<String, Object>) map;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Pterodactyl returned invalid JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Object raw) throws IOException {
        if (!(raw instanceof Map<?, ?> item)) throw new IOException("Pterodactyl returned an invalid resource");
        Object attributes = item.get("attributes");
        if (!(attributes instanceof Map<?, ?> map)) {
            throw new IOException("Pterodactyl resource has no attributes");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> root, String first, String second) {
        Object outer = root.get(first);
        if (!(outer instanceof Map<?, ?> map)) return Map.of();
        Object inner = map.get(second);
        return inner instanceof Map<?, ?> nested ? (Map<String, Object>) nested : Map.of();
    }

    private static String errorDetail(String body) {
        try {
            Object parsed = JsonUtil.parseJson(body);
            if (parsed instanceof Map<?, ?> root && root.get("errors") instanceof List<?> errors && !errors.isEmpty()
                    && errors.get(0) instanceof Map<?, ?> error) {
                String detail = text(error.get("detail"));
                if (!detail.isBlank()) return detail.substring(0, Math.min(detail.length(), 1_000));
            }
        } catch (Exception ignored) {
            // Fall through to a bounded generic response below.
        }
        String detail = body.isBlank() ? "Request rejected" : body.replaceAll("\\s+", " ").trim();
        return detail.substring(0, Math.min(detail.length(), 1_000));
    }

    private static int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Pterodactyl returned a non-numeric API value");
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static final class ApiFailure extends IOException {
        private final int status;

        private ApiFailure(int status, String detail) {
            super("Pterodactyl API returned HTTP " + status + ": " + detail);
            this.status = status;
        }
    }
}

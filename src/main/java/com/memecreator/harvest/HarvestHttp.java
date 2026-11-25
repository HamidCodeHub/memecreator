package com.memecreator.harvest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** HTTP for the harvester: JSON lists and image downloads, with a User-Agent and a size cap. */
@Component
public class HarvestHttp {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final HarvestProperties props;

    public HarvestHttp(HarvestProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public JsonNode getJson(String url) {
        try (InputStream in = send(url, "application/json")) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + url + ": " + e.getMessage(), e);
        }
    }

    /** The image bytes; refuses responses larger than meme.harvest.max-image-size. */
    public byte[] getBytes(String url) {
        long max = props.maxImageSize().toBytes();
        try (InputStream in = send(url, "image/*")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    throw new IOException("larger than " + props.maxImageSize());
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot download " + url + ": " + e.getMessage(), e);
        }
    }

    private InputStream send(String url, String accept) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("only https URLs are fetched");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(props.readTimeout())
                .header("User-Agent", props.userAgent())
                .header("Accept", accept)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}

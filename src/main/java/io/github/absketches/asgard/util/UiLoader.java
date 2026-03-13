package io.github.absketches.asgard.util;

import org.nanonative.nano.services.http.model.ContentType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UiLoader — loads static UI files from src/main/resources/asgard/ into memory at startup.
 */
public final class UiLoader {

    private static final String MANIFEST      = "asgard/asgard-files.txt";
    private static final String RESOURCE_BASE = "asgard/";

    public static final Map<String, byte[]> STATIC_FILES = new ConcurrentHashMap<>();

    private UiLoader() {}

    /**
     * Loads all files listed in asgard/asgard-files.txt into STATIC_FILES.
     * Called once from DashboardService.start() — caller handles logging.
     *
     * @throws IOException if the manifest is missing or a listed file cannot be read
     */
    public static void load() throws IOException {
        final InputStream manifest = UiLoader.class.getClassLoader().getResourceAsStream(MANIFEST);
        if (manifest == null) {
            throw new IOException("asgard-files.txt not found in JAR — was the build run with mvn package?");
        }
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(manifest))) {
            String fileName;
            while ((fileName = reader.readLine()) != null) {
                fileName = fileName.trim();
                if (fileName.isEmpty()) continue;
                try (InputStream stream = UiLoader.class.getClassLoader()
                    .getResourceAsStream(RESOURCE_BASE + fileName)) {
                    if (stream == null) {
                        throw new IOException("UI file listed in manifest but missing from JAR: " + fileName);
                    }
                    STATIC_FILES.put(fileName, stream.readAllBytes());
                }
            }
        }
    }

    public static String contentTypeFor(final String fileName) {
        if (fileName.endsWith(".html")) return ContentType.TEXT_HTML.value();
        if (fileName.endsWith(".js"))   return ContentType.APPLICATION_JAVASCRIPT.value();
        if (fileName.endsWith(".css"))  return ContentType.TEXT_CSS.value();
        if (fileName.endsWith(".json")) return ContentType.APPLICATION_JSON.value();
        if (fileName.endsWith(".svg"))  return ContentType.IMAGE_SVG.value();
        if (fileName.endsWith(".ico"))  return "image/x-icon";
        return ContentType.APPLICATION_OCTET_STREAM.value();
    }
}

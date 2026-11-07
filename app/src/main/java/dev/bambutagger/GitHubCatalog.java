package dev.bambutagger;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Public community repository navigator with local cached fallbacks. */
public final class GitHubCatalog {
    public static final String DEFAULT_REPO = "queengooborg/Bambu-Lab-RFID-Library";
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private final File cacheDir;
    private String repository;

    public static final class Entry {
        public final String name;
        public final String path;
        public final boolean directory;
        public final String downloadUrl;
        public Entry(String name, String path, boolean directory, String downloadUrl) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.downloadUrl = downloadUrl;
        }
    }

    public GitHubCatalog(Context context, String repository) {
        this.cacheDir = new File(context.getFilesDir(), "github-cache");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new IllegalStateException("Cannot initialize cache");
        setRepository(repository);
    }

    public synchronized void setRepository(String next) {
        if (!REPO.matcher(next).matches()) throw new IllegalArgumentException("Expected a GitHub owner/repository name");
        repository = next;
    }

    public synchronized String getRepository() { return repository; }

    private static String encodePath(String path) throws Exception {
        StringBuilder out = new StringBuilder();
        for (String bit : path.split("/")) {
            if (bit.isEmpty()) continue;
            if (out.length() != 0) out.append('/');
            out.append(URLEncoder.encode(bit, "UTF-8").replace("+", "%20"));
        }
        return out.toString();
    }

    public List<Entry> list(String path) throws Exception {
        String url = "https://api.github.com/repos/" + getRepository() + "/contents" +
                (path.isEmpty() ? "" : "/" + encodePath(path));
        JSONArray data = new JSONArray(fetchText(url, 12 * 3600_000L));
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject e = data.getJSONObject(i);
            boolean directory = e.optString("type").equals("dir");
            if (!directory && !e.optString("name").endsWith("-dump.json")) continue;
            entries.add(new Entry(e.getString("name"), e.getString("path"), directory,
                    e.optString("download_url", "")));
        }
        entries.sort((a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return entries;
    }

    public BambuDump loadDump(String uidDirectory) throws Exception {
        List<Entry> entries = list(uidDirectory);
        for (Entry e : entries) {
            if (!e.directory && e.name.endsWith("-dump.json")) {
                if (!e.downloadUrl.startsWith("https://raw.githubusercontent.com/"))
                    throw new SecurityException("Untrusted download URL");
                return BambuDump.fromJson(fetchText(e.downloadUrl.replace(" ", "%20"), 30L * 86400_000L));
            }
        }
        throw new IllegalArgumentException("No supported -dump.json found in this UID folder");
    }

    private static byte[] readAll(InputStream stream, int maxBytes) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            data.write(buffer, 0, count);
            if (data.size() > maxBytes) throw new IllegalArgumentException("Repository response is too large");
        }
        return data.toByteArray();
    }

    private static String digest(String url) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        return Hex.encode(hash);
    }

    private String fetchText(String address, long maxAge) throws Exception {
        URL url = new URL(address);
        String hostname = url.getHost();
        if (!"https".equals(url.getProtocol()) ||
                !(hostname.equals("api.github.com") || hostname.equals("raw.githubusercontent.com"))) {
            throw new SecurityException("Only the public GitHub API and raw GitHub files are supported");
        }
        File cached = new File(cacheDir, digest(address) + ".json");
        if (cached.isFile() && System.currentTimeMillis() - cached.lastModified() < maxAge) {
            try (InputStream in = new FileInputStream(cached)) {
                return new String(readAll(in, 2_000_000), StandardCharsets.UTF_8);
            }
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "BambuTagger-Android/0.1");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("GitHub returned " + connection.getResponseCode() +
                    ". You may have hit the anonymous API rate limit; try again after it resets.");
            }
            String result;
            try (InputStream in = connection.getInputStream()) {
                result = new String(readAll(in, 2_000_000), StandardCharsets.UTF_8);
            }
            File temp = new File(cacheDir, cached.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(result.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(cached)) {
                // The fetched data is still usable if cache replacement fails.
                temp.delete();
            }
            return result;
        } catch (Exception error) {
            if (cached.isFile()) {
                try (InputStream in = new FileInputStream(cached)) {
                    return new String(readAll(in, 2_000_000), StandardCharsets.UTF_8);
                }
            }
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

package io.mrarm.irc.util;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import coil.Coil;
import coil.request.ImageRequest;
import coil.transform.CircleCropTransformation;
import io.mrarm.chatlib.irc.IRCConnection;
import io.mrarm.irc.ServerConnectionInfo;
import io.mrarm.irc.config.ServerConfigData;

public class AvatarUtils {

    public static final String OAUTH_TOKEN_URL = "https://api.chateagratis.net/oauth/v2/token";
    public static final String CLIENT_ID = "24_2oldq1z88um8k8g4ksk4w088ws0skgkg4o8o8gockkg408ss04";
    public static final String CLIENT_SECRET = "1cobscjcltxc48ggs4g84c8ckowco08gs8s4o84wgc884coggo";
    public static final String CASDOOR_GET_ACCOUNT = "https://login.chateagratis.net/api/get-account";
    public static final String AVATAR_CDN = "https://chat-avatars.chateagratis.net";
    public static final String AVATAR_URL_PREFIX = "https://chat-avatars.chateagratis.net/avatar/Chat/";

    private static final Map<String, String> sOwnAvatarUrls = new ConcurrentHashMap<>();
    // Shared executor for avatar operations
    private static final ExecutorService sAvatarExecutor = Executors.newSingleThreadExecutor();

    /**
     * Shuts down the shared avatar executor.
     * Should be called when the application is terminating.
     */
    public static void shutdown() {
        sAvatarExecutor.shutdown();
        try {
            if (!sAvatarExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                sAvatarExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sAvatarExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class AvatarPreconnectResult {
        public final String pictureUrl;
        public final String gecosBase64;

        public AvatarPreconnectResult(String pictureUrl, String gecosBase64) {
            this.pictureUrl = pictureUrl;
            this.gecosBase64 = gecosBase64;
        }
    }

    public static boolean isChateagratisServer(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT).trim();
        return h.equalsIgnoreCase("irc.chatsfree.net") ||
                h.equalsIgnoreCase("irc.chatzona.org") ||
                h.equalsIgnoreCase("irc.chateagratis.net") ||
                h.equals("5.39.71.48") ||
                h.equals("51.255.67.106") ||
                h.equals("54.36.122.39");
    }

    public static String stripExtensionFromChateagratisUrl(String url) {
        if (url == null) return null;
        String clean = url.trim();
        int qIdx = clean.indexOf('?');
        String query = "";
        if (qIdx != -1) {
            query = clean.substring(qIdx);
            clean = clean.substring(0, qIdx);
        }
        int hIdx = clean.indexOf('#');
        String hash = "";
        if (hIdx != -1) {
            hash = clean.substring(hIdx);
            clean = clean.substring(0, hIdx);
        }
        if (clean.toLowerCase(Locale.ROOT).endsWith(".png")) {
            clean = clean.substring(0, clean.length() - 4);
        } else if (clean.toLowerCase(Locale.ROOT).endsWith(".jpg")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        return clean + query + hash;
    }

    private static String encodeBase64NoWrap(byte[] input) {
        try {
            return Base64.encodeToString(input, Base64.NO_WRAP);
        } catch (Throwable t) {
            try {
                Class<?> clazz = Class.forName("java.util.Base64");
                Object encoder = clazz.getMethod("getEncoder").invoke(null);
                if (encoder != null) {
                    return (String) encoder.getClass().getMethod("encodeToString", byte[].class).invoke(encoder, (Object) input);
                }
            } catch (Exception ignored) {
            }
            return "";
        }
    }

    private static byte[] decodeBase64(String input) {
        try {
            return Base64.decode(input, Base64.DEFAULT);
        } catch (Throwable t) {
            try {
                Class<?> clazz = Class.forName("java.util.Base64");
                Object decoder = clazz.getMethod("getDecoder").invoke(null);
                if (decoder != null) {
                    return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, input);
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static byte[] decodeBase64Url(String input) {
        if (input == null) return null;
        String base64 = input.replace('-', '+').replace('_', '/');
        int mod = base64.length() % 4;
        if (mod == 2) {
            base64 = base64 + "==";
        } else if (mod == 3) {
            base64 = base64 + "=";
        }
        return decodeBase64(base64);
    }

    /**
     * Builds the Base64 encoded GECOS string from a full avatar URL.
     * Output format before Base64 encoding: "#|#|#|" + path (without .png extension)
     */
    public static String buildGecos(String fullAvatarUrl) {
        String path = fullAvatarUrl == null ? "" : fullAvatarUrl
                .replaceFirst("(?i)^https?://(images|chat-avatars)\\.chateagratis\\.net", "");
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);

        if (path.toLowerCase(Locale.ROOT).endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        } else if (path.toLowerCase(Locale.ROOT).endsWith(".jpg")) {
            path = path.substring(0, path.length() - 4);
        }

        if (path.replaceAll("^/+", "").isEmpty()) {
            path = "#";
        } else if (!path.toLowerCase(Locale.ROOT).contains("default")) {
            path = path + "?v=" + System.currentTimeMillis();
        }

        String raw = "#|#|#|" + path;
        return encodeBase64NoWrap(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static String buildGecosFromAvatarUrl(String fullAvatarUrl) {
        return buildGecos(fullAvatarUrl);
    }

    /**
     * Decodes a Base64 encoded GECOS string and extracts the avatar URL if present.
     */
    public static String getAvatarUrlFromGecos(String gecos) {
        if (gecos == null) {
            return null;
        }
        String trimmed = gecos.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = decodeBase64(trimmed);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            if (!decoded.startsWith("#|#|#|")) {
                return null; // No parsear GECOS basura
            }
            String[] parts = decoded.split("\\|", -1);
            if (parts.length >= 4) {
                String path = parts[3].trim();
                if (!path.isEmpty() && !"#".equals(path)) {
                    String fullPath = path.startsWith("/") ? path : "/" + path;
                    if (fullPath.toLowerCase(Locale.ROOT).contains("/avatar/")) {
                        return stripExtensionFromChateagratisUrl(AVATAR_CDN + fullPath);
                    }
                }
            }
        } catch (Exception ignored) {
            // Decoding failed or not a valid GECOS
        }
        return null;
    }

    public static void clearImageCache(Context context) {
        if (context == null) return;
        try {
            coil.ImageLoader loader = Coil.imageLoader(context);
            if (loader.getMemoryCache() != null) {
                loader.getMemoryCache().clear();
            }
            if (loader.getDiskCache() != null) {
                loader.getDiskCache().clear();
            }
        } catch (Exception ignored) {}
    }

    public static String ensureTimestamp(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String clean = url.trim();
        int qIdx = clean.indexOf('?');
        String query = "";
        if (qIdx != -1) {
            query = clean.substring(qIdx);
            clean = clean.substring(0, qIdx);
        }
        int hIdx = clean.indexOf('#');
        String hash = "";
        if (hIdx != -1) {
            hash = clean.substring(hIdx);
            clean = clean.substring(0, hIdx);
        }

        if (clean.isEmpty() || clean.endsWith("/")) {
            return url;
        }

        if (query.isEmpty() || query.equalsIgnoreCase("?v=") || query.matches("(?i).*?[?&]v=$")) {
            if (query.isEmpty() || query.equalsIgnoreCase("?v=")) {
                query = "?v=" + System.currentTimeMillis();
            } else {
                query = query + System.currentTimeMillis();
            }
        } else if (!query.contains("v=")) {
            query = query + "&v=" + System.currentTimeMillis();
        } else {
            query = query.replaceAll("(?i)([?&])v=[^&]*", "$1v=" + System.currentTimeMillis());
        }

        return clean + query + hash;
    }

    /**
     * Fallback method to get avatar URL for a given nick in lowercase without extension.
     */
    public static String getAvatarUrlForNick(String nick) {
        if (nick == null || nick.trim().isEmpty()) {
            return null;
        }
        return AVATAR_URL_PREFIX + nick.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Stores own avatar URL for current connection.
     */
    public static void setOwnAvatarUrl(ServerConnectionInfo info, String pictureUrl) {
        if (info == null || pictureUrl == null) return;
        String cleanUrl = stripExtensionFromChateagratisUrl(pictureUrl);
        sOwnAvatarUrls.put(info.getUUID().toString(), cleanUrl);
        if (info.getServerConfig() != null) {
            info.getServerConfig().avatarUrl = cleanUrl;
        }
    }

    /**
     * Gets own avatar URL if stored for current connection.
     */
    public static String getOwnAvatarUrl(ServerConnectionInfo info) {
        if (info == null) return null;
        String cached = sOwnAvatarUrls.get(info.getUUID().toString());
        if (cached != null && !cached.isEmpty()) return cached;
        if (info.getServerConfig() != null && info.getServerConfig().avatarUrl != null && !info.getServerConfig().avatarUrl.isEmpty()) {
            return info.getServerConfig().avatarUrl;
        }
        return null;
    }

    /**
     * Convenience method to resolve avatar URL using own avatar URL, GECOS first, then fallback to nick/account.
     */
    public static String getAvatarUrl(ServerConnectionInfo info, String gecos, String nick, String account) {
        if (info != null && nick != null && nick.equalsIgnoreCase(info.getUserNick())) {
            String ownUrl = getOwnAvatarUrl(info);
            if (ownUrl != null && !ownUrl.isEmpty()) {
                return ownUrl;
            }
        }
        String url = getAvatarUrlFromGecos(gecos);
        if (url != null) {
            return url;
        }
        String avatarName = (account != null && !account.trim().isEmpty()) ? account.trim() : nick;
        return getAvatarUrlForNick(avatarName);
    }

    public static String getAvatarUrl(String gecos, String nick, String account) {
        return getAvatarUrl(null, gecos, nick, account);
    }

    /**
     * Loads an avatar into an ImageView trying GECOS URL / own URL first, then nick variants as fallbacks.
     */
    public static void loadAvatar(Context context, ServerConnectionInfo info, ImageView imageView, String gecos, String nick, String account, boolean circleCrop) {
        if (context == null || imageView == null) {
            return;
        }
        String primaryUrl = getAvatarUrl(info, gecos, nick, account);
        String avatarName = (account != null && !account.trim().isEmpty()) ? account.trim() : nick;

        List<String> candidates = new ArrayList<>();
        if (primaryUrl != null) {
            for (String c : getAvatarCandidates(primaryUrl)) {
                if (!candidates.contains(c)) candidates.add(c);
            }
        }
        if (avatarName != null && !avatarName.trim().isEmpty()) {
            String cleanName = avatarName.trim().toLowerCase(Locale.ROOT);
            String candidate1 = AVATAR_URL_PREFIX + cleanName;
            for (String c : getAvatarCandidates(candidate1)) {
                if (!candidates.contains(c)) candidates.add(c);
            }
        }

        // Ensure each candidate URL has a timestamp to bypass cache
        for (int i = 0; i < candidates.size(); i++) {
            String url = candidates.get(i);
            if (url != null && !url.isEmpty()) {
                String timedUrl = ensureTimestamp(url);
                if (timedUrl != null) {
                    candidates.set(i, timedUrl);
                }
            }
        }

        if (candidates.isEmpty()) {
            Log.w("ChateaGratisAvatar", "picture está vacío y no hay candidatos de avatar para " + nick);
            imageView.setVisibility(View.GONE);
            return;
        }

        imageView.setVisibility(View.VISIBLE);
        imageView.setImageTintList(null);
        imageView.setColorFilter(null);

        enqueueAvatarCandidate(context, imageView, candidates, 0, circleCrop, nick != null ? nick : "desconocido");
    }

    public static void loadAvatar(Context context, ImageView imageView, String gecos, String nick, String account, boolean circleCrop) {
        loadAvatar(context, null, imageView, gecos, nick, account, circleCrop);
    }

    private static void enqueueAvatarCandidate(Context context, ImageView imageView, List<String> candidates, int index, boolean circleCrop, String targetNick) {
        if (index >= candidates.size() || imageView == null) {
            Log.e("ChateaGratisAvatar", "Reporte de error: Fallaron todas las URLs de avatar o picture estuvo vacío para " + targetNick);
            return;
        }
        String url = candidates.get(index);
        ImageRequest.Builder builder = new ImageRequest.Builder(context)
                .data(url)
                .target(imageView)
                .listener(new ImageRequest.Listener() {
                    @Override
                    public void onError(ImageRequest request, coil.request.ErrorResult result) {
                        Log.w("ChateaGratisAvatar", "Error o 404 al cargar avatar desde URL: " + url + " para " + targetNick, result.getThrowable());
                        if (index + 1 < candidates.size()) {
                            enqueueAvatarCandidate(context, imageView, candidates, index + 1, circleCrop, targetNick);
                        } else {
                            Log.e("ChateaGratisAvatar", "Reporte de error: No se pudo cargar la imagen de avatar para " + targetNick);
                        }
                    }
                });
        if (circleCrop) {
            builder.transformations(new CircleCropTransformation());
        }
        Coil.imageLoader(context).enqueue(builder.build());
    }

    /**
     * Synchronously fetches OAuth token, parses JWT picture claim (or CASDOOR_GET_ACCOUNT), and constructs GECOS.
     */
    public static AvatarPreconnectResult prepareChateagratisAvatarGecosSync(String username, String password) {
        String accessToken = null;
        String pictureUrl = null;

        // 1) Obtain OAuth token
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            try {
                String query = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                        + "&client_secret=" + URLEncoder.encode(CLIENT_SECRET, "UTF-8")
                        + "&grant_type=password"
                        + "&username=" + URLEncoder.encode(username, "UTF-8")
                        + "&password=" + URLEncoder.encode(password, "UTF-8");
                URL tokenUrl = new URL(OAUTH_TOKEN_URL + "?" + query);
                HttpURLConnection tokenConn = (HttpURLConnection) tokenUrl.openConnection();
                tokenConn.setRequestMethod("GET");
                tokenConn.setConnectTimeout(5000);
                tokenConn.setReadTimeout(5000);

                if (tokenConn.getResponseCode() == 200) {
                    try (InputStreamReader reader = new InputStreamReader(tokenConn.getInputStream(), StandardCharsets.UTF_8)) {
                        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                        if (json.has("access_token") && !json.get("access_token").isJsonNull()) {
                            accessToken = json.get("access_token").getAsString();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("ChateaGratisAvatar", "Error al obtener OAuth token para " + username, e);
            }
        }

        Log.i("ChateaGratisAvatar", "access_token recibido (longitud: " + (accessToken != null ? accessToken.length() : 0) + ")");

        // 2) Read "picture" claim from JWT access_token
        if (accessToken != null) {
            String[] jwtParts = accessToken.split("\\.");
            if (jwtParts.length >= 2) {
                try {
                    byte[] payloadBytes = decodeBase64Url(jwtParts[1]);
                    if (payloadBytes != null) {
                        String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
                        JsonObject payloadObj = JsonParser.parseString(payloadJson).getAsJsonObject();
                        if (payloadObj.has("picture") && !payloadObj.get("picture").isJsonNull()) {
                            pictureUrl = payloadObj.get("picture").getAsString();
                        } else if (payloadObj.has("avatar") && !payloadObj.get("avatar").isJsonNull()) {
                            pictureUrl = payloadObj.get("avatar").getAsString();
                        }
                    }
                } catch (Exception e) {
                    Log.e("ChateaGratisAvatar", "Error al decodificar JWT payload", e);
                }
            }
        }

        // Fallback: GET CASDOOR_GET_ACCOUNT
        if (pictureUrl == null || pictureUrl.trim().isEmpty()) {
            try {
                URL accUrl = new URL(CASDOOR_GET_ACCOUNT);
                HttpURLConnection accConn = (HttpURLConnection) accUrl.openConnection();
                accConn.setConnectTimeout(5000);
                accConn.setReadTimeout(5000);
                if (accessToken != null) {
                    accConn.setRequestProperty("Authorization", "Bearer " + accessToken);
                } else if (password != null) {
                    accConn.setRequestProperty("Authorization", "Bearer " + password);
                }
                if (accConn.getResponseCode() == 200) {
                    try (InputStreamReader accReader = new InputStreamReader(accConn.getInputStream(), StandardCharsets.UTF_8)) {
                        JsonObject accJson = JsonParser.parseReader(accReader).getAsJsonObject();
                        if (accJson.has("data") && !accJson.get("data").isJsonNull()) {
                            JsonObject data = accJson.getAsJsonObject("data");
                            if (data.has("avatar") && !data.get("avatar").isJsonNull()) {
                                pictureUrl = data.get("avatar").getAsString();
                            } else if (data.has("picture") && !data.get("picture").isJsonNull()) {
                                pictureUrl = data.get("picture").getAsString();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("ChateaGratisAvatar", "Error al llamar a CASDOOR_GET_ACCOUNT", e);
            }
        }

        if (pictureUrl == null || pictureUrl.trim().isEmpty()) {
            String safeUsername = (username != null && !username.trim().isEmpty()) ? username.trim().toLowerCase(Locale.ROOT) : "unknown";
            pictureUrl = AVATAR_URL_PREFIX + safeUsername;
            Log.w("ChateaGratisAvatar", "picture/avatar URL recibida: está VACÍA o no encontrada, usando fallback: " + pictureUrl);
        } else {
            pictureUrl = stripExtensionFromChateagratisUrl(pictureUrl);
            Log.i("ChateaGratisAvatar", "picture/avatar URL recibida: " + pictureUrl);
        }

        // 4) Construct GECOS
        String gecosBase64 = buildGecos(pictureUrl);
        Log.i("ChateaGratisAvatar", "GECOS generado: " + gecosBase64);

        return new AvatarPreconnectResult(pictureUrl, gecosBase64);
    }

    /**
     * Sends SETNAME command ~1s after connect (001).
     */
    public static void sendSetnameAfterConnect(ServerConnectionInfo info, String gecosBase64) {
        if (info == null || gecosBase64 == null) return;
        sAvatarExecutor.execute(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
            if (info.getApiInstance() instanceof IRCConnection) {
                IRCConnection conn = (IRCConnection) info.getApiInstance();
                String cmd = "SETNAME :" + gecosBase64;
                conn.sendCommandRaw(cmd, null, null);
                Log.i("ChateaGratisAvatar", "comando SETNAME reenviado tras 001: " + cmd);
            }
        });
    }

    /**
     * Legacy entry point for compatibility if called asynchronously.
     */
    public static void updateChateagratisAvatarGecos(ServerConnectionInfo info) {
        if (info == null || !isChateagratisServer(info.getHost())) {
            return;
        }
        ServerConfigData config = info.getServerConfig();
        String username = config != null && config.authUser != null && !config.authUser.trim().isEmpty() ? config.authUser.trim() :
                (config != null && config.user != null && !config.user.trim().isEmpty() ? config.user.trim() : info.getUserNick());
        String password = config != null && config.authPass != null && !config.authPass.trim().isEmpty() ? config.authPass.trim() :
                (config != null && config.pass != null && !config.pass.trim().isEmpty() ? config.pass.trim() : null);

        if (username == null || username.isEmpty()) {
            return;
        }

        sAvatarExecutor.execute(() -> {
            AvatarPreconnectResult result = prepareChateagratisAvatarGecosSync(username, password);
            if (result.pictureUrl != null && !result.pictureUrl.isEmpty()) {
                setOwnAvatarUrl(info, result.pictureUrl);
            }
            sendSetnameAfterConnect(info, result.gecosBase64);
        });
    }

    public static List<String> getAvatarCandidates(String url) {
        List<String> candidates = new ArrayList<>();
        if (url == null || url.trim().isEmpty()) return candidates;

        int qIdx = url.indexOf('?');
        String query = qIdx != -1 ? url.substring(qIdx) : "";
        String clean = qIdx != -1 ? url.substring(0, qIdx) : url;
        int hIdx = clean.indexOf('#');
        String hash = hIdx != -1 ? clean.substring(hIdx) : "";
        clean = hIdx != -1 ? clean.substring(0, hIdx) : clean;

        addCandidateIfUnique(candidates, clean + query + hash);

        String basePath = clean;
        if (basePath.toLowerCase(Locale.ROOT).endsWith(".png")) {
            basePath = basePath.substring(0, basePath.length() - 4);
        } else if (basePath.toLowerCase(Locale.ROOT).endsWith(".jpg")) {
            basePath = basePath.substring(0, basePath.length() - 4);
        } else if (basePath.toLowerCase(Locale.ROOT).endsWith(".jpeg")) {
            basePath = basePath.substring(0, basePath.length() - 5);
        } else if (basePath.toLowerCase(Locale.ROOT).endsWith(".webp")) {
            basePath = basePath.substring(0, basePath.length() - 5);
        }

        String prefix = AVATAR_URL_PREFIX;
        String name = basePath;
        if (basePath.startsWith(prefix)) {
            name = basePath.substring(prefix.length());
        } else {
            int lastSlash = basePath.lastIndexOf('/');
            if (lastSlash != -1) {
                prefix = basePath.substring(0, lastSlash + 1);
                name = basePath.substring(lastSlash + 1);
            }
        }

        List<String> nameVariants = new ArrayList<>();
        if (name != null && !name.isEmpty()) {
            nameVariants.add(name);
            String lower = name.toLowerCase(Locale.ROOT);
            if (!nameVariants.contains(lower)) nameVariants.add(lower);

            String sanitized = lower.replaceAll("[^a-z0-9_-]", "");
            if (!sanitized.isEmpty() && !nameVariants.contains(sanitized)) {
                nameVariants.add(sanitized);
            }

            String sanitizedNoUnderscores = sanitized.replaceAll("^_+|_+$", "");
            if (!sanitizedNoUnderscores.isEmpty() && !nameVariants.contains(sanitizedNoUnderscores)) {
                nameVariants.add(sanitizedNoUnderscores);
            }

            try {
                String encoded = android.net.Uri.encode(lower);
                if (!encoded.isEmpty() && !nameVariants.contains(encoded)) {
                    nameVariants.add(encoded);
                }
            } catch (Exception ignored) {}
        }

        for (String n : nameVariants) {
            addCandidateIfUnique(candidates, prefix + n + query + hash);
            addCandidateIfUnique(candidates, prefix + n + ".png" + query + hash);
            addCandidateIfUnique(candidates, prefix + n + ".jpg" + query + hash);
            addCandidateIfUnique(candidates, prefix + n + ".jpeg" + query + hash);
            addCandidateIfUnique(candidates, prefix + n + ".webp" + query + hash);
        }

        if (!query.isEmpty()) {
            for (String n : nameVariants) {
                addCandidateIfUnique(candidates, prefix + n);
                addCandidateIfUnique(candidates, prefix + n + ".png");
                addCandidateIfUnique(candidates, prefix + n + ".jpg");
                addCandidateIfUnique(candidates, prefix + n + ".jpeg");
                addCandidateIfUnique(candidates, prefix + n + ".webp");
            }
        }

        return candidates;
    }

    private static void addCandidateIfUnique(List<String> list, String item) {
        if (!list.contains(item)) {
            list.add(item);
        }
    }

    public static List<String> ensureWaitCommand(List<String> cmds) {
        return ensureWaitCommand(cmds, null);
    }

    public static List<String> ensureWaitCommand(List<String> cmds, String host) {
        if (cmds == null || cmds.isEmpty())
            return cmds;
        String first = cmds.get(0).trim().replaceAll("^/", "");
        if (first.toLowerCase(Locale.ROOT).startsWith("wait")) {
            return cmds;
        }
        List<String> modified = new ArrayList<>();
        int waitTime = (host != null && isChateagratisServer(host)) ? 10 : 3;
        modified.add("wait " + waitTime);
        modified.addAll(cmds);
        return modified;
    }
}

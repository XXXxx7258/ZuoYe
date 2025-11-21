import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云音乐服务：搜索、热评、直链、下载。
 */
public final class MusicService {
    private static final String PRIMARY_BASE = "https://163api.qijieya.cn";
    private static final String FALLBACK_BASE = "https://music.txqq.pro/";

    private final HttpClient http;
    private final Path musicDir;

    public MusicService(Path musicDir) {
        this.musicDir = musicDir;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        try {
            Files.createDirectories(musicDir);
        } catch (IOException ignored) {
        }
    }

    public List<MusicMatch> search(String keyword, int limit) {
        List<MusicMatch> list = searchPrimary(keyword, limit);
        if (list.isEmpty()) {
            list = searchFallback(keyword, limit);
        }
        return list;
    }

    public List<MusicComment> hotComments(String songId, int limit) {
        List<MusicComment> list = fetchCommentsFromBase(PRIMARY_BASE, songId, limit);
        if (list.isEmpty()) {
            list = fetchCommentsFromBase(FALLBACK_BASE, songId, limit);
        }
        return list;
    }

    private List<MusicComment> fetchCommentsFromBase(String base, String songId, int limit) {
        List<MusicComment> list = new ArrayList<>();
        try {
            String url = base + "/comment/hot?id=" + URLEncoder.encode(songId, StandardCharsets.UTF_8) + "&type=0";
            HttpResponse<String> res = sendGet(url);
            if (res == null) {
                return list;
            }
            list = parseComments(res.body(), limit);
            if (list.isEmpty()) {
                list = parseCommentsFallback(res.body(), limit);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public String downloadMusic(String musicUrl, String entryId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(musicUrl))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String ext = guessExtension(musicUrl);
                Path target = musicDir.resolve(entryId + ext);
                Files.write(target, resp.body());
                return target.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private List<MusicMatch> searchPrimary(String keyword, int limit) {
        List<MusicMatch> list = new ArrayList<>();
        try {
            String url = PRIMARY_BASE + "/search?keywords=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&limit=" + limit + "&type=1";
            HttpResponse<String> res = sendGet(url);
            if (res == null) {
                return list;
            }
            Object root = MiniJson.parse(res.body());
            if (!(root instanceof Map)) {
                return list;
            }
            Object result = ((Map<?, ?>) root).get("result");
            if (!(result instanceof Map)) {
                return list;
            }
            Object songsObj = ((Map<?, ?>) result).get("songs");
            if (!(songsObj instanceof List)) {
                return list;
            }
            for (Object o : (List<?>) songsObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> song = (Map<?, ?>) o;
                String id = String.valueOf(asNumber(song.get("id")));
                String title = safeString(song.get("name"));
                String artist = joinArtists(song.get("artists"));
                String cover = coverFromAlbum(song.get("album"));
                String audioUrl = fetchSongUrlPrimary(id);
                list.add(new MusicMatch(id, title, artist, audioUrl, cover));
                if (list.size() >= limit) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private List<MusicMatch> searchFallback(String keyword, int limit) {
        List<MusicMatch> list = new ArrayList<>();
        try {
            String body = "input=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&filter=name&type=netease&page=1";
            HttpRequest req = HttpRequest.newBuilder(URI.create(FALLBACK_BASE))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                return list;
            }
            Object root = MiniJson.parse(res.body());
            if (!(root instanceof Map)) {
                return list;
            }
            Object songsObj = ((Map<?, ?>) root).get("songs");
            if (!(songsObj instanceof List)) {
                return list;
            }
            for (Object o : (List<?>) songsObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> song = (Map<?, ?>) o;
                String id = safeString(song.get("songid"));
                String title = safeString(song.get("title"));
                String artist = safeString(song.get("author"));
                String url = safeString(song.get("url"));
                String cover = safeString(song.get("pic"));
                if (url == null || url.isBlank()) {
                    url = fetchSongUrlPrimary(id);
                }
                list.add(new MusicMatch(id, title, artist, url, cover));
                if (list.size() >= limit) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private String fetchSongUrlPrimary(String id) {
        try {
            String url = PRIMARY_BASE + "/song/url?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            HttpResponse<String> res = sendGet(url);
            if (res == null) {
                return "";
            }
            Object root = MiniJson.parse(res.body());
            if (!(root instanceof Map)) {
                return "";
            }
            Object data = ((Map<?, ?>) root).get("data");
            if (data instanceof List && !((List<?>) data).isEmpty()) {
                Object first = ((List<?>) data).get(0);
                if (first instanceof Map) {
                    return safeString(((Map<?, ?>) first).get("url"));
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private HttpResponse<String> sendGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return res;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String joinArtists(Object artists) {
        if (!(artists instanceof List)) {
            return "";
        }
        List<?> list = (List<?>) artists;
        List<String> names = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) {
                names.add(safeString(((Map<?, ?>) o).get("name")));
            }
        }
        return String.join("、", names);
    }

    private String coverFromAlbum(Object album) {
        if (album instanceof Map) {
            return safeString(((Map<?, ?>) album).get("picUrl"));
        }
        return "";
    }

    private String valueFromUser(Object userObj) {
        if (userObj instanceof Map) {
            return safeString(((Map<?, ?>) userObj).get("nickname"));
        }
        return "";
    }

    private Number asNumber(Object v) {
        if (v instanceof Number) {
            return (Number) v;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ex) {
            return 0;
        }
    }

    private String safeString(Object o) {
        return o == null ? "" : o.toString();
    }

    private String guessExtension(String url) {
        int qIndex = url.indexOf('?');
        if (qIndex > 0) {
            url = url.substring(0, qIndex);
        }
        int idx = url.lastIndexOf('.');
        if (idx > 0 && idx < url.length() - 1 && idx > url.lastIndexOf('/')) {
            return url.substring(idx);
        }
        return ".mp3";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String parseLyric(String body) {
        Object root = MiniJson.parse(body);
        if (root instanceof Map) {
            Object lrc = ((Map<?, ?>) root).get("lrc");
            if (lrc instanceof Map) {
                String text = safeString(((Map<?, ?>) lrc).get("lyric"));
                if (!text.isBlank()) {
                    return text;
                }
            }
            Object pure = ((Map<?, ?>) root).get("pureMusic");
            if (pure instanceof Boolean && ((Boolean) pure)) {
                return "纯音乐，请欣赏。";
            }
        }
        // 兜底：尝试正则提取 \"lyric\":\"...\" 段
        Matcher m = Pattern.compile("\\\"lyric\\\":\\\"(.*?)\\\"", Pattern.DOTALL).matcher(body);
        if (m.find()) {
            return unescape(m.group(1)).replace("\\\\n", "\n");
        }
        return "";
    }

    private List<MusicComment> parseComments(String body, int limit) {
        List<MusicComment> list = new ArrayList<>();
        Object root = MiniJson.parse(body);
        if (!(root instanceof Map)) {
            return list;
        }
        Object hot = ((Map<?, ?>) root).get("hotComments");
        if (!(hot instanceof List)) {
            return list;
        }
        List<?> arr = (List<?>) hot;
        for (Object o : arr) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> c = (Map<?, ?>) o;
            String nick = valueFromUser(c.get("user"));
            String time = safeString(c.get("timeStr"));
            if (time.isBlank()) {
                long epoch = asNumber(c.get("time")).longValue();
                if (epoch > 0) {
                    time = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(epoch));
                }
            }
            String liked = String.valueOf(asNumber(c.get("likedCount")));
            String content = safeString(c.get("content"));
            list.add(new MusicComment(nick, time, liked, content));
            if (list.size() >= limit) {
                break;
            }
        }
        return list;
    }

    private List<MusicComment> parseCommentsFallback(String body, int limit) {
        List<MusicComment> list = new ArrayList<>();
        Pattern p = Pattern.compile("\\\"nickname\\\":\\\"(.*?)\\\".*?\\\"content\\\":\\\"(.*?)\\\".*?\\\"likedCount\\\":(\\d+).*?\\\"time\\\":(\\d+)", Pattern.DOTALL);
        Matcher m = p.matcher(body);
        while (m.find() && list.size() < limit) {
            String nick = unescape(m.group(1));
            String content = unescape(m.group(2)).replace("\\\\n", "\n");
            String liked = m.group(3);
            long epoch = 0L;
            try {
                epoch = Long.parseLong(m.group(4));
            } catch (NumberFormatException ignored) {
            }
            String time = epoch > 0 ? DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epoch)) : "";
            list.add(new MusicComment(nick, time, liked, content));
        }
        return list;
    }

    public String lyric(String songId) {
        // 优先主源
        try {
            String url = PRIMARY_BASE + "/lyric?id=" + URLEncoder.encode(songId, StandardCharsets.UTF_8);
            HttpResponse<String> res = sendGet(url);
            if (res != null) {
                String lrc = parseLyric(res.body());
                if (!lrc.isBlank()) {
                    return lrc;
                }
            }
        } catch (Exception ignored) {
        }
        // 备用源（只要兼容 txqq 返回的 lrc 字段）
        try {
            String url = FALLBACK_BASE + "lyric?id=" + URLEncoder.encode(songId, StandardCharsets.UTF_8);
            HttpResponse<String> res = sendGet(url);
            if (res != null) {
                String lrc = parseLyric(res.body());
                if (!lrc.isBlank()) {
                    return lrc;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String unescape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\/", "/").replace("\\\\", "\\").replace("\\\"", "\"");
    }

    public static final class MusicMatch {
        public final String id;
        public final String title;
        public final String artist;
        public final String url;
        public final String cover;

        MusicMatch(String id, String title, String artist, String url, String cover) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.url = url;
            this.cover = cover;
        }

        public String toJson() {
            return "{\"id\":\"" + escape(id) + "\",\"title\":\"" + escape(title)
                + "\",\"artist\":\"" + escape(artist) + "\",\"url\":\"" + escape(url)
                + "\",\"cover\":\"" + escape(cover) + "\"}";
        }
    }

    public static final class MusicComment {
        public final String nick;
        public final String time;
        public final String liked;
        public final String content;

        MusicComment(String nick, String time, String liked, String content) {
            this.nick = nick;
            this.time = time;
            this.liked = liked;
            this.content = content;
        }

        public String toJson() {
            return "{\"nick\":\"" + escape(nick) + "\",\"time\":\"" + escape(time)
                + "\",\"liked\":\"" + escape(liked) + "\",\"content\":\"" + escape(content) + "\"}";
        }
    }
}

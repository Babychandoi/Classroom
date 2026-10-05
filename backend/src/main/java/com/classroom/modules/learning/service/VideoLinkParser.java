package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D-31: turns what an author pasted into (provider, id). Only https links of YouTube and Google Drive files are accepted; everything else -
 * look-alike hosts, userinfo, ports, http, folders, Docs - is a 400. The id is validated and the URLs are always rebuilt from it, so nothing the
 * author typed is ever echoed into an iframe.
 */
public final class VideoLinkParser {

    public static final String YOUTUBE = "YOUTUBE";
    public static final String GOOGLE_DRIVE = "GOOGLE_DRIVE";
    public static final String MESSAGE = "Chỉ hỗ trợ liên kết video YouTube hoặc Google Drive (dạng https://…)";

    public record Parsed(String provider, String ref) {}

    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
            "youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final Pattern YOUTUBE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern DRIVE_ID = Pattern.compile("^[A-Za-z0-9_-]{6,128}$");
    private static final Set<String> YOUTUBE_PATH_KINDS = Set.of("embed", "shorts", "live", "v");
    private static final Set<String> DRIVE_FILE_ACTIONS = Set.of("view", "preview", "edit");
    public static final Pattern STORED_REF = Pattern.compile("^[A-Za-z0-9_-]{6,128}$");

    private VideoLinkParser() {}

    private static AppException bad() {
        return new AppException(ErrorCode.BAD_REQUEST, MESSAGE);
    }

    /** @throws AppException 400 when the text is not a supported https YouTube / Google Drive file link */
    public static Parsed parse(String raw) {
        if (raw == null) throw bad();
        String text = raw.replaceAll("\\s+", "");
        if (text.isEmpty() || text.length() > 2048) throw bad();
        URI uri;
        try {
            uri = new URI(text);
        } catch (Exception e) {
            throw bad();
        }
        if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT))) throw bad();
        if (uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getHost() == null) throw bad();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        List<String> seg = List.of(path.split("/")).stream().filter(s -> !s.isEmpty()).toList();
        String query = uri.getRawQuery();

        if (YOUTUBE_HOSTS.contains(host)) {
            String id = null;
            if (host.equals("youtu.be")) {
                if (seg.size() >= 1) id = seg.get(0);
            } else if (seg.size() == 1 && seg.get(0).equals("watch")) {
                id = queryParam(query, "v");
            } else if (seg.size() >= 2 && YOUTUBE_PATH_KINDS.contains(seg.get(0))) {
                id = seg.get(1);
            }
            if (id == null || !YOUTUBE_ID.matcher(id).matches()) throw bad();
            return new Parsed(YOUTUBE, id);
        }
        if (host.equals("drive.google.com")) {
            String id = null;
            if (seg.size() >= 3 && seg.get(0).equals("file") && seg.get(1).equals("d")) {
                if (seg.size() == 3 || DRIVE_FILE_ACTIONS.contains(seg.get(3))) id = seg.get(2);
            } else if (seg.size() >= 5 && seg.get(0).equals("file") && seg.get(1).equals("u") && seg.get(2).matches("\\d{1,2}")
                    && seg.get(3).equals("d")) {
                if (seg.size() == 5 || DRIVE_FILE_ACTIONS.contains(seg.get(5))) id = seg.get(4);
            } else if (seg.size() == 1 && (seg.get(0).equals("open") || seg.get(0).equals("uc"))) {
                id = queryParam(query, "id");
            }
            if (id == null || !DRIVE_ID.matcher(id).matches()) throw bad();
            return new Parsed(GOOGLE_DRIVE, id);
        }
        throw bad();
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    public static String canonicalUrl(String provider, String ref) {
        if (YOUTUBE.equals(provider)) return "https://www.youtube.com/watch?v=" + ref;
        if (GOOGLE_DRIVE.equals(provider)) return "https://drive.google.com/file/d/" + ref + "/view";
        return null;
    }

    public static String embedUrl(String provider, String ref) {
        if (YOUTUBE.equals(provider)) return "https://www.youtube-nocookie.com/embed/" + ref;
        if (GOOGLE_DRIVE.equals(provider)) return "https://drive.google.com/file/d/" + ref + "/preview";
        return null;
    }
}

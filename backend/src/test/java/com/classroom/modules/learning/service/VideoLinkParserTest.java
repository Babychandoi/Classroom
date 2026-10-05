package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

/** D-31: VideoLinkParser accepts only https YouTube / Google Drive file links and rebuilds URLs from the validated id. */
class VideoLinkParserTest {

    @ParameterizedTest
    @CsvSource({
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ, YOUTUBE, dQw4w9WgXcQ",
            "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42s, YOUTUBE, dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123, YOUTUBE, dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc&t=3, YOUTUBE, dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ, YOUTUBE, dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ, YOUTUBE, dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ, YOUTUBE, dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?feature=share, YOUTUBE, dQw4w9WgXcQ",
            "https://www.youtube.com/v/dQw4w9WgXcQ, YOUTUBE, dQw4w9WgXcQ",
            "'  https://youtu.be/dQw4w9WgXcQ  ', YOUTUBE, dQw4w9WgXcQ",
            "HTTPS://YouTu.be/dQw4w9WgXcQ, YOUTUBE, dQw4w9WgXcQ",
            "https://drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/view?usp=sharing, GOOGLE_DRIVE, 1AbC_dEf-GhIjKlMnOp",
            "https://drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/preview, GOOGLE_DRIVE, 1AbC_dEf-GhIjKlMnOp",
            "https://drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/edit, GOOGLE_DRIVE, 1AbC_dEf-GhIjKlMnOp",
            "https://drive.google.com/open?id=1AbC_dEf-GhIjKlMnOp, GOOGLE_DRIVE, 1AbC_dEf-GhIjKlMnOp",
            "https://drive.google.com/uc?id=1AbC_dEf-GhIjKlMnOp&export=download, GOOGLE_DRIVE, 1AbC_dEf-GhIjKlMnOp",
            "https://drive.google.com/uc?export=download&id=1AbC_dEf-GhIjKlMnOp, GOOGLE_DRIVE, 1AbC_dEf-GhIjKlMnOp",
            "https://drive.google.com/file/u/1/d/1AbC_dEf-GhIjKlMnOp/view, GOOGLE_DRIVE, 1AbC_dEf-GhIjKlMnOp",
    })
    void accepts(String url, String provider, String id) {
        VideoLinkParser.Parsed p = VideoLinkParser.parse(url.replace("'", ""));
        assertEquals(provider, p.provider());
        assertEquals(id, p.ref());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "not a url", "javascript:alert(1)", "data:text/html,<script>alert(1)</script>",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",              // http
            "http://drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/view",
            "//www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",    // look-alike hosts
            "https://evilyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com.evil.example/embed/dQw4w9WgXcQ",
            "https://notyoutu.be/dQw4w9WgXcQ",
            "https://drive.google.com.evil.example/file/d/1AbC_dEf-GhIjKlMnOp/view",
            "https://evil.example/drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/view",
            "https://user@www.youtube.com/watch?v=dQw4w9WgXcQ",        // userinfo
            "https://www.youtube.com@evil.example/watch?v=dQw4w9WgXcQ",
            "https://user:pw@drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/view",
            "https://www.youtube.com:8443/watch?v=dQw4w9WgXcQ",        // port
            "https://www.youtube.com/watch?v=short",                   // invalid ids
            "https://www.youtube.com/watch?v=dQw4w9WgXcQtoolong",
            "https://www.youtube.com/watch?v=dQw4w9WgX<Q",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/channel/UCabcdefghijk",
            "https://www.youtube.com/playlist?list=PL1234567890",
            "https://youtu.be/",
            "https://drive.google.com/file/d/ab/view",
            "https://drive.google.com/drive/folders/1AbC_dEf-GhIjKlMnOp", // folders / listings
            "https://drive.google.com/drive/u/0/my-drive",
            "https://drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/delete",
            "https://drive.google.com/open?id=",
            "https://docs.google.com/document/d/1AbC_dEf-GhIjKlMnOp/edit",
            "https://www.google.com/",
            "ftp://www.youtube.com/watch?v=dQw4w9WgXcQ",
    })
    void rejects(String url) {
        AppException e = assertThrows(AppException.class, () -> VideoLinkParser.parse(url), url);
        assertEquals(VideoLinkParser.MESSAGE, e.getMessage());
    }

    @Test
    void nullIsRejectedAndUrlsAreRebuiltFromTheId() {
        assertThrows(AppException.class, () -> VideoLinkParser.parse(null));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", VideoLinkParser.canonicalUrl("YOUTUBE", "dQw4w9WgXcQ"));
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", VideoLinkParser.embedUrl("YOUTUBE", "dQw4w9WgXcQ"));
        assertEquals("https://drive.google.com/file/d/abcdef12/view", VideoLinkParser.canonicalUrl("GOOGLE_DRIVE", "abcdef12"));
        assertEquals("https://drive.google.com/file/d/abcdef12/preview", VideoLinkParser.embedUrl("GOOGLE_DRIVE", "abcdef12"));
        assertNull(VideoLinkParser.embedUrl("UPLOAD", "x"));
    }
}

package com.classroom.modules.community.dto;

import java.util.List;

/**
 * R5-01: page envelope for the keyset-paginated feed listing. Visibility filtering (including the
 * SEGMENT/PRODUCT_OWNER rules that cannot be expressed in SQL) happens before a batch is
 * considered part of the page, so {@code posts} always holds exactly the requested page size (or
 * fewer only when the feed is exhausted). {@code nextCursor} is an opaque token identifying the
 * position of the last post in this page; pass it back as the {@code cursor} query param to fetch
 * the next page. It is {@code null} when {@code hasNext} is false.
 */
public class FeedPageDto {
    private List<PostDto> posts;
    private String nextCursor;
    private boolean hasNext;

    public FeedPageDto() {}

    public FeedPageDto(List<PostDto> posts, String nextCursor, boolean hasNext) {
        this.posts = posts;
        this.nextCursor = nextCursor;
        this.hasNext = hasNext;
    }

    public List<PostDto> getPosts() {
        return posts;
    }

    public void setPosts(List<PostDto> posts) {
        this.posts = posts;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(String nextCursor) {
        this.nextCursor = nextCursor;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }
}

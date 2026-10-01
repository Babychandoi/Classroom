package com.classroom.modules.community.dto;

import java.util.List;

/**
 * R20-03: one page of older comments of a post ("Xem thêm bình luận"). {@code comments} are oldest-first; when {@code hasMore} is
 * true, pass {@code nextBefore} (the id of the oldest comment in this page) as the {@code before} parameter of the next request.
 */
public class CommentPageDto {
    private List<CommentDto> comments;
    private boolean hasMore;
    private String nextBefore;

    public CommentPageDto() {}

    public CommentPageDto(List<CommentDto> comments, boolean hasMore, String nextBefore) {
        this.comments = comments;
        this.hasMore = hasMore;
        this.nextBefore = nextBefore;
    }

    public List<CommentDto> getComments() { return comments; }
    public void setComments(List<CommentDto> comments) { this.comments = comments; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    public String getNextBefore() { return nextBefore; }
    public void setNextBefore(String nextBefore) { this.nextBefore = nextBefore; }
}

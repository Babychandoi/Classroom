package com.classroom.modules.blog.dto;

import java.util.List;

/** D-27: one keyset page of blog posts; {@code nextCursor} is null on the last page. */
public record BlogPostPageDto(List<BlogPostDto> items, String nextCursor) {}

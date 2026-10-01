package com.classroom.modules.classroom.dto;

import java.util.List;

/**
 * R20-03: one page of the Studio roster. {@code total} counts every member matching the filters (not only this page), {@code page}
 * is 0-based, {@code hasNext} tells the client whether "Xem thêm" has anything to load.
 */
public class ClassMemberPageDto {
    private List<ClassMemberDto> members;
    private long total;
    private int page;
    private int size;
    private boolean hasNext;

    public ClassMemberPageDto() {}

    public ClassMemberPageDto(List<ClassMemberDto> members, long total, int page, int size, boolean hasNext) {
        this.members = members;
        this.total = total;
        this.page = page;
        this.size = size;
        this.hasNext = hasNext;
    }

    public List<ClassMemberDto> getMembers() { return members; }
    public void setMembers(List<ClassMemberDto> members) { this.members = members; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public boolean isHasNext() { return hasNext; }
    public void setHasNext(boolean hasNext) { this.hasNext = hasNext; }
}

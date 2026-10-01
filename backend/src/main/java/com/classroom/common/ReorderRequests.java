package com.classroom.common;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R16-10: shared validation for the "reorder" endpoints (courses, sections, lessons, questions).
 *
 * <p>The previous check was {@code ordered.size() == existing.size() && existing.containsAll(ordered)},
 * which accepts a list with a repeated id as long as its length matches: {@code [a, a, b]} against
 * {@code {a, b, c}} passes, so {@code a} is written twice and {@code c} is never repositioned - two
 * rows end up sharing a position and one is silently left out of the order. A valid reorder is
 * exactly a permutation of the existing ids.</p>
 */
public final class ReorderRequests {

    private ReorderRequests() {
    }

    /**
     * Whether {@code ordered} is a permutation of {@code existingIds}: no null or repeated entry,
     * every existing id present, nothing foreign.
     */
    public static boolean isPermutationOf(List<String> ordered, Collection<String> existingIds) {
        if (ordered == null || existingIds == null) {
            return false;
        }
        Set<String> distinct = new HashSet<>(ordered);
        Set<String> existing = new HashSet<>(existingIds);
        return ordered.size() == existingIds.size()
                && distinct.size() == ordered.size()
                && distinct.equals(existing);
    }
}

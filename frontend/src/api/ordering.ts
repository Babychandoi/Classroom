// R17-02/03: sections, lessons and exam questions were all created with a constant position (1 / unset /
// 0), so every new row tied with the existing ones and their relative order was left to the database.
// A new item must land after everything that is already there.

/**
 * Position for an item appended after `items`: one past the larger of the item count and the highest
 * existing position. Normally that is simply `items.length + 1`; the max() keeps it strictly after
 * the last item when positions are not contiguous (after a delete, or after the 0-based renumbering a
 * reorder produces), where `length + 1` alone could collide with a surviving row.
 */
export function nextPosition(items?: ReadonlyArray<{ position?: number }> | null): number {
  const list = items ?? [];
  const highest = list.reduce((max, item) => Math.max(max, item.position ?? 0), 0);
  return Math.max(list.length, highest) + 1;
}

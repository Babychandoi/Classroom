import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * A list read page by page (0-based; a page shorter than `size` is the last one). It reloads from page 0 whenever
 * `resetKey` changes (e.g. a scope tab), ignores answers of a superseded request, and keeps what is already shown when
 * "Xem thêm" fails (the error is reported separately as `moreError`).
 */
export function usePagedList<T>(load: (page: number) => Promise<T[]>, size: number, resetKey: string) {
  const [items, setItems] = useState<T[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [nextPage, setNextPage] = useState(1);
  const [attempt, setAttempt] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;
  const generation = useRef(0);

  useEffect(() => {
    const mine = ++generation.current;
    setLoading(true);
    setError(null);
    setMoreError(null);
    setItems([]);
    setHasMore(false);
    setNextPage(1);
    loadRef.current(0)
      .then((data) => {
        if (generation.current !== mine) return;
        const list = Array.isArray(data) ? data : [];
        setItems(list);
        setHasMore(list.length >= size);
      })
      .catch((err) => {
        if (generation.current !== mine) return;
        setError(err instanceof Error && err.message ? err.message : 'Không tải được dữ liệu. Vui lòng thử lại.');
      })
      .finally(() => {
        if (generation.current === mine) setLoading(false);
      });
  }, [resetKey, size, attempt]);

  const loadMore = useCallback(async () => {
    const mine = generation.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const data = await loadRef.current(nextPage);
      if (generation.current !== mine) return;
      const list = Array.isArray(data) ? data : [];
      setItems((prev) => [...prev, ...list]);
      setNextPage((p) => p + 1);
      setHasMore(list.length >= size);
    } catch (err) {
      if (generation.current !== mine) return;
      setMoreError(err instanceof Error && err.message ? err.message : 'Không tải thêm được. Vui lòng thử lại.');
    } finally {
      if (generation.current === mine) setLoadingMore(false);
    }
  }, [nextPage, size]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);

  return { items, setItems, loading, error, moreError, loadingMore, hasMore, loadMore, reload };
}

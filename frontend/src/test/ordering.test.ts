import { describe, it, expect } from 'vitest';
import { nextPosition } from '../api/ordering';

// R17-02/03: new sections, lessons and questions go after everything already there.
describe('nextPosition', () => {
  it('starts at 1 for an empty or missing list', () => {
    expect(nextPosition([])).toBe(1);
    expect(nextPosition(undefined)).toBe(1);
    expect(nextPosition(null)).toBe(1);
  });

  it('is length + 1 for contiguous 1-based positions', () => {
    expect(nextPosition([{ position: 1 }, { position: 2 }, { position: 3 }])).toBe(4);
  });

  it('is length + 1 for contiguous 0-based positions (what a reorder writes)', () => {
    expect(nextPosition([{ position: 0 }, { position: 1 }, { position: 2 }])).toBe(4);
  });

  it('stays strictly after the last item when positions have gaps (e.g. after a delete)', () => {
    // length + 1 alone would be 3 and collide with the surviving position 3
    expect(nextPosition([{ position: 2 }, { position: 3 }])).toBe(4);
  });

  it('copes with tied or missing positions', () => {
    expect(nextPosition([{ position: 0 }, { position: 0 }, {}])).toBe(4);
  });
});

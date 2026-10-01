import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ClassroomHeader } from '../components/ClassroomHeader';
import type { Classroom } from '../types';

/**
 * R17-05: at phone width the 8 tabs overflow and scroll sideways with no visible cue. The header fades
 * the edge that still has hidden tabs and scrolls the active tab into view when it changes (jsdom has no
 * layout, so the scroll geometry is faked).
 */

let activePath = '';

vi.mock('react-router-dom', () => ({
  NavLink: ({ children, to, className }: {
    children?: React.ReactNode;
    to: string;
    className?: string | ((s: { isActive: boolean }) => string);
  }) => {
    const isActive = to === activePath;
    return (
      <a href={to} aria-current={isActive ? 'page' : undefined} className={typeof className === 'function' ? className({ isActive }) : className}>
        {children}
      </a>
    );
  },
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const classroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 1,
  userRole: 'STUDENT',
  createdAt: new Date().toISOString(),
} as Classroom;

const TAB_WIDTH = 100;
const TAB_ORDER = ['feed', 'learn', 'exams', 'leaderboard', 'documents', 'members', 'about', 'store'];

// 8 tabs of 100px each; a strip 300px wide shows 3 of them. jsdom itself defines offsetWidth/offsetLeft on
// HTMLElement.prototype, so the originals are kept and put back after each test.
const FAKED = ['clientWidth', 'scrollWidth', 'offsetWidth', 'offsetLeft'] as const;
const originals = new Map<string, PropertyDescriptor | undefined>(
  FAKED.map((prop) => [prop, Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop)]),
);

const geometry = (clientWidth: number, scrollWidth: number) => {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => clientWidth });
  Object.defineProperty(HTMLElement.prototype, 'scrollWidth', { configurable: true, get: () => scrollWidth });
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => TAB_WIDTH });
  Object.defineProperty(HTMLElement.prototype, 'offsetLeft', {
    configurable: true,
    get(this: HTMLElement) {
      const tab = (this.getAttribute('href') ?? '').split('/').pop() ?? '';
      return Math.max(0, TAB_ORDER.indexOf(tab)) * TAB_WIDTH;
    },
  });
};

const tabStrip = () => screen.getByText('Bảng tin').closest('a')!.parentElement as HTMLElement;

describe('ClassroomHeader tab strip scroll affordance (R17-05)', () => {
  afterEach(() => {
    activePath = '';
    for (const prop of FAKED) {
      const original = originals.get(prop);
      if (original) Object.defineProperty(HTMLElement.prototype, prop, original);
      else delete (HTMLElement.prototype as any)[prop];
    }
  });

  it('fades only the right edge when tabs are hidden beyond it', () => {
    geometry(300, 800);
    render(<ClassroomHeader classroom={classroom} />);

    expect(screen.getByTestId('tabs-fade-right')).toBeInTheDocument();
    expect(screen.queryByTestId('tabs-fade-left')).not.toBeInTheDocument();
  });

  it('fades the left edge once scrolled, and drops the right one at the end of the strip', () => {
    geometry(300, 800);
    render(<ClassroomHeader classroom={classroom} />);
    const strip = tabStrip();

    strip.scrollLeft = 200;
    fireEvent.scroll(strip);
    expect(screen.getByTestId('tabs-fade-left')).toBeInTheDocument();
    expect(screen.getByTestId('tabs-fade-right')).toBeInTheDocument();

    strip.scrollLeft = 500; // 500 + 300 = 800 = scrollWidth
    fireEvent.scroll(strip);
    expect(screen.getByTestId('tabs-fade-left')).toBeInTheDocument();
    expect(screen.queryByTestId('tabs-fade-right')).not.toBeInTheDocument();
  });

  it('shows no fade when every tab fits (desktop)', () => {
    geometry(1200, 1200);
    render(<ClassroomHeader classroom={classroom} />);

    expect(screen.queryByTestId('tabs-fade-left')).not.toBeInTheDocument();
    expect(screen.queryByTestId('tabs-fade-right')).not.toBeInTheDocument();
  });

  it('scrolls a hidden active tab into view on arrival, then leaves the strip alone while the person scrolls it', () => {
    geometry(300, 800);
    activePath = '/classes/demo-class/store'; // the last tab: offsetLeft 700, hidden beyond the first 300px
    render(<ClassroomHeader classroom={classroom} />);
    const strip = tabStrip();

    // centred on the active tab: 700 - (300 - 100) / 2
    expect(strip.scrollLeft).toBe(600);

    // The person swipes back to the start. The scroll updates the fade state (a re-render) - that must
    // not snap the strip back to the active tab.
    strip.scrollLeft = 100;
    fireEvent.scroll(strip);
    expect(strip.scrollLeft).toBe(100);
    expect(screen.getByTestId('tabs-fade-left')).toBeInTheDocument();
  });

  it('does not move a strip whose active tab is already fully visible', () => {
    geometry(300, 800);
    activePath = '/classes/demo-class/learn'; // offsetLeft 100..200, inside 0..300
    render(<ClassroomHeader classroom={classroom} />);

    expect(tabStrip().scrollLeft).toBe(0);
  });
});

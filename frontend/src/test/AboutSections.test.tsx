import { fireEvent, render, screen } from '@testing-library/react';
import { AboutSections } from '../components/AboutSections';
import { describe, it, expect } from 'vitest';

describe('Giới thiệu nhiều mục', () => {
  it('supports keyboard tabs and keeps untrusted content as text', () => {
    render(<AboutSections sections={[
      { title: 'Mục tiêu', contentMarkdown: '<script>bad()</script>', imageUrl: '', imageAlt: '' },
      { title: 'Giảng viên', contentMarkdown: 'Nội dung khác', imageUrl: 'https://example.com/a.png', imageAlt: 'Giảng viên trong lớp' },
    ]} />);
    const first = screen.getByRole('tab', { name: 'Mục tiêu' });
    first.focus(); fireEvent.keyDown(first, { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: 'Giảng viên' })).toHaveFocus();
    expect(screen.getByRole('img')).toHaveAttribute('alt', 'Giảng viên trong lớp');
    expect(document.querySelector('script')).toBeNull();
    fireEvent.keyDown(document.activeElement!, { key: 'Home' });
    expect(first).toHaveFocus();
    expect(screen.getByRole('tabpanel')).toHaveTextContent('<script>bad()</script>');
  });
});

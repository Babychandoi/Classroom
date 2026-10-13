import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Empty, FilterPills, KPI, Notice, Ring, Segmented, Sheet, Tag, UnderlineTabs } from '../components/ds';

describe('ds primitives (D-35)', () => {
  it('Tag renders its label with the colour palette and an optional dot', () => {
    const { container } = render(<Tag color="green" dot>Đã nộp</Tag>);
    const tag = screen.getByText('Đã nộp');
    expect(tag).toHaveClass('text-green-700');
    expect(container.querySelector('[aria-hidden="true"]')).toHaveClass('bg-green-600');
  });

  it('Segmented is a radio group with one checked option and arrow-key navigation', () => {
    const Harness = () => {
      const [value, setValue] = React.useState<'a' | 'b' | 'c'>('a');
      return (
        <Segmented
          ariaLabel="Chế độ chấm"
          value={value}
          onChange={setValue}
          options={[{ key: 'a', label: 'Tự động' }, { key: 'b', label: 'Thủ công' }, { key: 'c', label: 'Kết hợp' }]}
        />
      );
    };
    render(<Harness />);
    const group = screen.getByRole('radiogroup', { name: 'Chế độ chấm' });
    expect(screen.getByRole('radio', { name: 'Tự động' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Thủ công' })).toHaveAttribute('tabindex', '-1');

    fireEvent.keyDown(group, { key: 'ArrowRight' });
    expect(screen.getByRole('radio', { name: 'Thủ công' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Thủ công' })).toHaveFocus();
    fireEvent.keyDown(group, { key: 'End' });
    expect(screen.getByRole('radio', { name: 'Kết hợp' })).toHaveAttribute('aria-checked', 'true');
    fireEvent.keyDown(group, { key: 'ArrowRight' }); // wraps
    expect(screen.getByRole('radio', { name: 'Tự động' })).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(screen.getByRole('radio', { name: 'Kết hợp' }));
    expect(screen.getByRole('radio', { name: 'Kết hợp' })).toHaveAttribute('aria-checked', 'true');
  });

  it('UnderlineTabs is a tablist whose selected tab is marked and wired to its panel', () => {
    const onChange = vi.fn();
    render(
      <UnderlineTabs
        ariaLabel="Bài tập & đề"
        idPrefix="asmt"
        value="mine"
        onChange={onChange}
        items={[{ key: 'mine', label: 'Đề của tôi', count: 3 }, { key: 'bank', label: 'Ngân hàng câu hỏi' }]}
      />,
    );
    expect(screen.getByRole('tablist', { name: 'Bài tập & đề' })).toBeInTheDocument();
    const mine = screen.getByRole('tab', { name: /Đề của tôi/ });
    expect(mine).toHaveAttribute('aria-selected', 'true');
    expect(mine).toHaveAttribute('aria-controls', 'asmt-panel-mine');
    fireEvent.click(screen.getByRole('tab', { name: 'Ngân hàng câu hỏi' }));
    expect(onChange).toHaveBeenCalledWith('bank');
  });

  it('FilterPills are toggle buttons with counts', () => {
    const onChange = vi.fn();
    render(<FilterPills ariaLabel="Lọc" value="all" onChange={onChange} items={[{ key: 'all', label: 'Tất cả' }, { key: 'todo', label: 'Cần chấm', count: 4, alert: true }]} />);
    expect(screen.getByRole('button', { name: 'Tất cả' })).toHaveAttribute('aria-pressed', 'true');
    const todo = screen.getByRole('button', { name: /Cần chấm/ });
    expect(todo).toHaveAttribute('aria-pressed', 'false');
    expect(todo).toHaveTextContent('4');
    fireEvent.click(todo);
    expect(onChange).toHaveBeenCalledWith('todo');
  });

  it('Ring exposes its value as a meter; KPI, Notice and Empty render their content', () => {
    render(
      <>
        <Ring value={72.4} ariaLabel="Tỉ lệ đúng" />
        <KPI label="Đã nộp" value="18/24" delta="+3" />
        <Notice role="status">Đề đang ở bản nháp.</Notice>
        <Empty title="Chưa có đề nào" sub="Tạo đề đầu tiên." />
      </>,
    );
    expect(screen.getByRole('meter', { name: 'Tỉ lệ đúng' })).toHaveAttribute('aria-valuenow', '72');
    expect(screen.getByText('72%')).toBeInTheDocument();
    expect(screen.getByText('18/24')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Đề đang ở bản nháp.');
    expect(screen.getByText('Chưa có đề nào')).toBeInTheDocument();
  });

  it('Sheet is a labelled modal dialog that closes on Escape and on its close button', () => {
    const onClose = vi.fn();
    const { rerender } = render(<Sheet open title="Chi tiết bài nộp" onClose={onClose}><p>Nội dung</p></Sheet>);
    const dialog = screen.getByRole('dialog', { name: 'Chi tiết bài nộp' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    fireEvent.keyDown(document, { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(2);
    rerender(<Sheet open={false} title="Chi tiết bài nộp" onClose={onClose}><p>Nội dung</p></Sheet>);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

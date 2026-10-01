import React, { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Modal } from '../components/Modal';

/**
 * R18-01: every modal goes through this component. The overlay must scroll and the panel must be height-capped so a
 * dialog taller than the viewport can still be reached; it must also behave as an accessible dialog.
 */

describe('Modal (R18-01)', () => {
  it('scrolls the overlay and caps + scrolls the panel so tall dialogs stay reachable', () => {
    const { container } = render(
      <Modal title="Phân quyền Trợ giảng">
        <div style={{ height: 3000 }}>rất dài</div>
      </Modal>,
    );
    const overlay = container.querySelector('[data-modal-overlay]') as HTMLElement;
    expect(overlay.className).toContain('fixed');
    expect(overlay.className).toContain('inset-0');
    expect(overlay.className).toContain('overflow-y-auto');

    const panel = screen.getByRole('dialog');
    expect(panel.className).toContain('max-h-[90vh]');
    expect(panel.className).toContain('overflow-y-auto');
  });

  it('is an accessible modal dialog named by its title', () => {
    render(<Modal title="Sửa khóa học"><p>nội dung</p></Modal>);
    const dialog = screen.getByRole('dialog', { name: 'Sửa khóa học' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('heading', { name: 'Sửa khóa học' })).toBeInTheDocument();
  });

  it('uses aria-label when the caller renders its own header, and supports alertdialog', () => {
    render(<Modal ariaLabel="Trạng thái thanh toán" role="alertdialog"><p>x</p></Modal>);
    expect(screen.getByRole('alertdialog', { name: 'Trạng thái thanh toán' })).toBeInTheDocument();
  });

  it('closes on Escape only when an onClose handler is provided', () => {
    const onClose = vi.fn();
    const { unmount } = render(<Modal title="A" onClose={onClose}><button>ok</button></Modal>);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
    unmount();

    render(<Modal title="B"><button>ok</button></Modal>);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('only the topmost of two stacked modals reacts to Escape', () => {
    const closeBottom = vi.fn();
    const closeTop = vi.fn();
    render(
      <>
        <Modal title="dưới" onClose={closeBottom}><button>1</button></Modal>
        <Modal title="trên" onClose={closeTop}><button>2</button></Modal>
      </>,
    );
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(closeTop).toHaveBeenCalledTimes(1);
    expect(closeBottom).not.toHaveBeenCalled();
  });

  it('moves focus into the dialog on open and back to the opener on close', () => {
    const Harness: React.FC = () => {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button onClick={() => setOpen(true)}>Mở</button>
          {open && (
            <Modal title="Hộp thoại" onClose={() => setOpen(false)}>
              <input aria-label="Tên" />
              <button onClick={() => setOpen(false)}>Đóng</button>
            </Modal>
          )}
        </>
      );
    };
    render(<Harness />);
    const opener = screen.getByRole('button', { name: 'Mở' });
    opener.focus();
    fireEvent.click(opener);
    expect(screen.getByLabelText('Tên')).toHaveFocus();

    fireEvent.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('keeps Tab and Shift+Tab inside the dialog', () => {
    render(
      <>
        <button>ngoài</button>
        <Modal title="Bẫy tiêu điểm">
          <button>đầu</button>
          <button>cuối</button>
        </Modal>
      </>,
    );
    const first = screen.getByRole('button', { name: 'đầu' });
    const last = screen.getByRole('button', { name: 'cuối' });
    expect(first).toHaveFocus();

    last.focus();
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(first).toHaveFocus();

    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();
  });
});

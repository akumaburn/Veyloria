import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useMapControls2D } from './useMapControls2D';

function Harness({ ready = true }: { ready?: boolean }) {
  const c = useMapControls2D(800, ready);
  return ready ? <>
    <div data-testid="viewport" ref={c.containerRef} onKeyDown={c.handleKeyDown}
      onPointerDown={c.handlePointerDown} onPointerMove={c.handlePointerMove}
      onPointerUp={c.handlePointerUp} onPointerCancel={c.handlePointerUp}>
      <div ref={c.transformRef} data-testid="transform" />
    </div>
    <output>{Math.round(c.zoomLevel * 100)}%</output>
    <button onClick={c.resetView}>Fit</button>
  </> : null;
}

const bounds = { width: 1000, height: 500, left: 0, top: 0, right: 1000, bottom: 500, x: 0, y: 0, toJSON() {} };

describe('2D map controls', () => {
  it('attaches wheel zoom when the viewport mounts after loading', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(bounds);
    const { rerender } = render(<Harness ready={false} />);
    rerender(<Harness ready />);
    expect(screen.getByText('50%')).toBeInTheDocument();
    fireEvent.wheel(screen.getByTestId('viewport'), { deltaY: -100, clientX: 250, clientY: 250 });
    expect(screen.queryByText('50%')).not.toBeInTheDocument();
    const zoomed = screen.getByTestId('transform').style.transform;
    fireEvent.wheel(screen.getByTestId('viewport'), { deltaY: 0, deltaX: 100 });
    expect(screen.getByTestId('transform').style.transform).toBe(zoomed);
  });

  it('uses the same fitted view for Home and the toolbar', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(bounds);
    render(<Harness />);
    const fit = screen.getByTestId('transform').style.transform;
    fireEvent.keyDown(screen.getByTestId('viewport'), { key: '+' });
    fireEvent.keyDown(screen.getByTestId('viewport'), { key: 'ArrowDown' });
    expect(screen.getByTestId('transform').style.transform).not.toBe(fit);
    fireEvent.keyDown(screen.getByTestId('viewport'), { key: 'Home' });
    expect(screen.getByTestId('transform').style.transform).toBe(fit);
    fireEvent.keyDown(screen.getByTestId('viewport'), { key: '-', ctrlKey: true });
    expect(screen.getByTestId('transform').style.transform).toBe(fit);
  });

  it('refits when the viewport dimensions change', () => {
    let resize: ResizeObserverCallback | undefined;
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: ResizeObserverCallback) { resize = callback; }
      observe() {}
      disconnect() {}
    });
    const spy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(bounds);
    const { unmount } = render(<Harness />);
    spy.mockReturnValue({ ...bounds, width: 500, height: 800 });
    act(() => resize?.([], {} as ResizeObserver));
    expect(screen.getByText('100%')).toBeInTheDocument();
    expect(screen.getByTestId('transform')).toHaveStyle({ transform: 'translate(0px, 150px) scale(1)' });
    unmount();
    vi.unstubAllGlobals();
  });
  it('pinches around the finger midpoint and continues panning after lifting one finger', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(bounds);
    render(<Harness />);
    const viewport = screen.getByTestId('viewport');
    viewport.setPointerCapture = vi.fn();
    viewport.hasPointerCapture = vi.fn(() => true);
    viewport.releasePointerCapture = vi.fn();
    const pointer = (type: string, pointerId: number, clientX: number, clientY: number) => {
      const event = new MouseEvent(type, { bubbles: true, button: 0, clientX, clientY });
      Object.defineProperty(event, 'pointerId', { value: pointerId });
      Object.defineProperty(event, 'pointerType', { value: 'touch' });
      fireEvent(viewport, event);
    };
    pointer('pointerdown', 1, 400, 250);
    pointer('pointerdown', 2, 600, 250);
    pointer('pointermove', 2, 800, 250);
    expect(screen.getByText('100%')).toBeInTheDocument();
    expect(screen.getByTestId('transform')).toHaveStyle({ transform: 'translate(100px, -250px) scale(1)' });
    pointer('pointerup', 2, 800, 250);
    pointer('pointermove', 1, 420, 270);
    expect(screen.getByTestId('transform')).toHaveStyle({ transform: 'translate(120px, -230px) scale(1)' });
    pointer('pointercancel', 1, 420, 270);
    pointer('pointermove', 1, 460, 300);
    expect(screen.getByTestId('transform')).toHaveStyle({ transform: 'translate(120px, -230px) scale(1)' });
  });

});

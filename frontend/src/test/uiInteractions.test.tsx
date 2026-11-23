import { useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { SettingRow, SettingInput, SettingToggle, SettingSlider, SettingSelect } from '@/components/settings/SettingsComponents';
import { Input } from '@/components/ui/Input';
import { Select } from '@/components/ui/Select';
import { Textarea } from '@/components/ui/Textarea';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { ChatInput } from '@/components/chat/ChatInput';

describe('Shared UI interactions', () => {
  it('links fields to unique labels and validation messages', () => {
    render(<>
      <Input label="Name" error="Name is required" aria-describedby="extra-help" />
      <Input label="Email" error="Email is required" />
      <Select label="Model" options={[]} helperText="Choose a model" />
      <Textarea label="Biography" error="Biography is too long" />
    </>);
    expect(screen.getByLabelText('Name')).toHaveAccessibleDescription('Name is required');
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription('Email is required');
    expect(screen.getByLabelText('Model')).toHaveAccessibleDescription('Choose a model');
    expect(screen.getByLabelText('Biography')).toHaveAccessibleDescription('Biography is too long');
    expect(screen.getByLabelText('Name').getAttribute('aria-describedby')).toContain('extra-help');
    const ids = [...document.querySelectorAll('[id]')].map(node => node.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('names settings controls from their surrounding row', () => {
    render(<>
      <SettingRow label="Temperature"><SettingSlider value={0.7} onChange={vi.fn()} min={0} max={2} /></SettingRow>
      <SettingRow label="Sound"><SettingToggle checked onChange={vi.fn()} /></SettingRow>
      <SettingRow label="Token limit"><SettingInput value={100} onChange={vi.fn()} type="number" /></SettingRow>
      <SettingRow label="Theme"><SettingSelect value="dark" onChange={vi.fn()} options={[{value: 'dark', label: 'Dark'}]} /></SettingRow>
    </>);
    expect(screen.getByRole('slider', {name: 'Temperature'})).toBeInTheDocument();
    expect(screen.getByRole('switch', {name: 'Sound'})).toBeInTheDocument();
    expect(screen.getByRole('spinbutton', {name: 'Token limit'})).toBeInTheDocument();
    expect(screen.getByRole('combobox', {name: 'Theme'})).toBeInTheDocument();
  });

  it('preserves loading button names and prevents accidental form submission', () => {
    const submit = vi.fn();
    const { rerender } = render(<form onSubmit={submit}><Button>Save</Button></form>);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(submit).not.toHaveBeenCalled();
    rerender(<Button isLoading>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('contains modal focus and respects Escape settings', async () => {
    const user = userEvent.setup();
    const close = vi.fn();
    const { rerender } = render(<Modal isOpen onClose={close} title="Edit character" closeOnEscape={false}><button>First</button><button>Last</button></Modal>);
    const first = screen.getByRole('button', { name: 'Close modal' });
    await waitFor(() => expect(first).toHaveFocus());
    screen.getByRole('button', { name: 'Last' }).focus();
    await user.tab();
    expect(first).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(close).not.toHaveBeenCalled();
    rerender(<Modal isOpen onClose={close} title="Edit character"><button>First</button></Modal>);
    await user.keyboard('{Escape}');
    expect(close).toHaveBeenCalledOnce();
  });

  it('returns focus to the button that opened the modal', async () => {
    const user = userEvent.setup();
    function Example() {
      const [open, setOpen] = useState(false);
      return <><button onClick={() => setOpen(true)}>Edit</button><Modal isOpen={open} onClose={() => setOpen(false)} title="Editor">Editor content</Modal></>;
    }
    render(<Example />);
    await user.click(screen.getByRole('button', {name: 'Edit'}));
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.getByRole('button', {name: 'Edit'})).toHaveFocus());
  });

  it('does not send during composition or a paused conversation', () => {
    const send = vi.fn();
    const { rerender } = render(<ChatInput value="Hello" onChange={vi.fn()} onSend={send} />);
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter', isComposing: true });
    expect(send).not.toHaveBeenCalled();
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    expect(send).toHaveBeenCalledOnce();
    rerender(<ChatInput value="Hello" onChange={vi.fn()} onSend={send} isPaused />);
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    expect(send).toHaveBeenCalledOnce();
  });
});

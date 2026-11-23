import { useState } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginForm } from '@/components/auth/LoginForm';
import { RegisterForm } from '@/components/auth/RegisterForm';
import CharacterForm, { defaultFormData } from '@/components/shared/CharacterForm';
import { Input } from '@/components/ui/Input';
import { Textarea, MarkdownTextarea } from '@/components/ui/Textarea';
import ProfilePage from '@/pages/ProfilePage';
import { CustomSelect } from '@/components/ui/Select';
import { Checkbox } from '@/components/ui/Checkbox';
import { SettingInput, SettingRow } from '@/components/settings/SettingsComponents';

const { mutation } = vi.hoisted(() => ({ mutation: vi.fn() }));
vi.mock('@apollo/client', async (original) => ({
  ...(await original<typeof import('@apollo/client')>()),
  useMutation: () => [mutation, { loading: false }],
}));
vi.mock('@/stores/authStore', () => ({
  useAuthStore: () => ({
    setAuth: vi.fn(),
    setUser: vi.fn(),
    user: {
      id: '1',
      username: 'person',
      email: 'person@example.com',
      displayName: 'Person',
      role: 'user',
    },
  }),
}));

beforeEach(() => mutation.mockResolvedValue({ data: {} }));

async function fillRegistration(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Username'), 'test_user');
  await user.type(screen.getByLabelText('Email Address'), 'person@example.com');
  await user.type(screen.getByLabelText('Password'), 'abcdefgh');
  await user.type(screen.getByLabelText('Confirm Password'), 'abcdefgh');
}

function CharacterEditor({ save }: { save: () => void }) {
  const [data, setData] = useState(defaultFormData);
  return (
    <CharacterForm
      formData={data}
      onChange={setData}
      onSubmit={save}
      isLoading={false}
      submitLabel="Create character"
    />
  );
}

describe('Forms fit their purpose', () => {
  it('submits sign-in once through native Enter without applying registration password rules', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <LoginForm />
      </MemoryRouter>,
    );
    await user.type(screen.getByLabelText(/^Username or email/), 'person');
    await user.type(screen.getByLabelText(/^Password/), 'short{Enter}');
    expect(mutation).toHaveBeenCalledTimes(1);
    expect(mutation).toHaveBeenCalledWith({ variables: { username: 'person', password: 'short' } });
  });

  it('focuses missing sign-in fields and displays rejected requests inline', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <LoginForm />
      </MemoryRouter>,
    );
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(screen.getByLabelText(/^Username or email/)).toHaveFocus();
    expect(mutation).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText(/^Username or email/), 'person');
    await user.type(screen.getByLabelText(/^Password/), 'password');
    mutation.mockRejectedValueOnce(new Error('Invalid credentials'));
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Check both fields and try again.');
  });

  it('accepts the backend password policy and explains missing terms consent', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <RegisterForm />
      </MemoryRouter>,
    );
    await fillRegistration(user);
    const submit = screen.getByRole('button', { name: 'Create account' });
    expect(submit).toBeEnabled();
    await user.click(submit);
    const terms = screen.getByRole('checkbox');
    expect(terms).toHaveFocus();
    expect(terms).toHaveAccessibleDescription('You must agree to the terms and conditions');
    expect(mutation).not.toHaveBeenCalled();
    await user.click(terms);
    await user.click(submit);
    expect(mutation).toHaveBeenCalledWith({
      variables: { username: 'test_user', email: 'person@example.com', password: 'abcdefgh' },
    });
  });

  it('keeps instructions with errors and returns focus after clearing an input', async () => {
    const user = userEvent.setup();
    function Field() {
      const [value, setValue] = useState('Existing value');
      return (
        <Input
          label="Name"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          clearable
          onClear={() => setValue('')}
          helperText="Use a recognizable name."
          error="Name is already taken."
        />
      );
    }
    render(<Field />);
    expect(screen.getByLabelText('Name')).toHaveAccessibleDescription(
      'Use a recognizable name. Name is already taken.',
    );
    await user.click(screen.getByRole('button', { name: 'Clear input' }));
    expect(screen.getByLabelText('Name')).toHaveValue('');
    expect(screen.getByLabelText('Name')).toHaveFocus();
  });

  it('enforces text limits and retains an uncontrolled draft through the expanded editor', async () => {
    const user = userEvent.setup();
    render(
      <Textarea label="Notes" defaultValue="Draft" fullscreenEnabled maxLength={12} showCount />,
    );
    expect(screen.getByLabelText('Notes')).toHaveAttribute('maxlength', '12');
    expect(screen.getByText('5 / 12')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Expand text editor' }));
    const editor = screen.getByRole('textbox', { name: 'Notes' });
    await user.type(editor, ' updated text');
    expect(editor).toHaveValue('Draft update');
    await user.keyboard('{Escape}');
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Expand text editor' })).toHaveFocus(),
    );
    expect(screen.getByLabelText('Notes')).toHaveValue('Draft update');
  });

  it('supports keyboard-native selection without changing numeric option types', async () => {
    const user = userEvent.setup();
    const change = vi.fn();
    render(
      <CustomSelect
        label="Limit"
        value={10}
        onChange={change}
        options={[
          { value: 10, label: 'Ten' },
          { value: 20, label: 'Twenty' },
        ]}
      />,
    );
    await user.selectOptions(screen.getByRole('combobox', { name: 'Limit' }), '20');
    expect(change).toHaveBeenCalledWith(20);
  });

  it('does not save zero when clearing a numeric setting', async () => {
    const user = userEvent.setup();
    const change = vi.fn();
    render(
      <SettingRow label="Token limit" description="Maximum response length.">
        <SettingInput type="number" value={2048} min={1} max={10000} onChange={change} />
      </SettingRow>,
    );
    const input = screen.getByRole('spinbutton', { name: 'Token limit' });
    expect(input).toHaveAccessibleDescription('Maximum response length.');
    await user.clear(input);
    expect(input).toHaveValue(null);
    expect(change).not.toHaveBeenCalled();
    await user.type(input, '4096');
    expect(change).toHaveBeenLastCalledWith(4096);
  });

  it('labels character fields, validates missing instructions, and saves a custom integer token limit', async () => {
    const user = userEvent.setup();
    const save = vi.fn();
    render(<CharacterEditor save={save} />);
    await user.click(screen.getByRole('button', { name: 'Create character' }));
    await waitFor(() => expect(screen.getByRole('textbox', { name: /^Name/ })).toHaveFocus());
    expect(save).not.toHaveBeenCalled();
    await user.type(screen.getByRole('textbox', { name: /^Name/ }), 'Guide');
    await user.click(screen.getByRole('tab', { name: 'Instructions' }));
    await user.type(
      screen.getByRole('textbox', { name: /^System prompt/ }),
      'Help explain complex ideas.',
    );
    await user.click(screen.getByRole('checkbox', { name: 'Limit response length' }));
    const tokenLimit = screen.getByRole('spinbutton', { name: 'Response token limit' });
    await user.clear(tokenLimit);
    await user.type(tokenLimit, '4096');
    expect(tokenLimit).toHaveAttribute('step', '1');
    await user.click(screen.getByRole('button', { name: 'Create character' }));
    expect(save).toHaveBeenCalledOnce();
  });

  it('starts feelings at neutral and configures both directions with tier controls', async () => {
    const user = userEvent.setup();
    render(<CharacterEditor save={vi.fn()} />);
    await user.click(screen.getByRole('tab', { name: 'Feelings' }));
    const characterFeeling = within(
      screen.getByRole('radiogroup', { name: 'This character feels toward you' }),
    );
    const userFeeling = within(
      screen.getByRole('radiogroup', { name: 'You feel toward this character' }),
    );
    expect(characterFeeling.getByRole('radio', { name: 'Neutral' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(userFeeling.getByRole('radio', { name: 'Neutral' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await user.click(characterFeeling.getByRole('radio', { name: 'Love' }));
    await user.click(userFeeling.getByRole('radio', { name: 'Dislike' }));
    expect(characterFeeling.getByRole('radio', { name: 'Love' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(userFeeling.getByRole('radio', { name: 'Dislike' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByText('True Neutral')).toBeInTheDocument();
  });

  it('keeps checkbox visuals synchronized when the field is uncontrolled', async () => {
    const user = userEvent.setup();
    render(<Checkbox label="Public" defaultChecked description="Visible to everyone." />);
    const input = screen.getByRole('checkbox', { name: 'Public' });
    expect(input).toBeChecked();
    expect(input).toHaveAccessibleDescription('Visible to everyone.');
    await user.click(input);
    expect(input).not.toBeChecked();
    expect(input).toHaveAttribute('aria-checked', 'false');
  });
  it('previews actual Markdown and preserves the draft when returning to edit', async () => {
    const user = userEvent.setup();
    render(<MarkdownTextarea label="Story" defaultValue="## Opening scene" />);
    expect(screen.getByLabelText('Story')).toHaveValue('## Opening scene');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(screen.getByRole('heading', { name: 'Opening scene' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    expect(screen.getByLabelText('Story')).toHaveValue('## Opening scene');
  });

  it('focuses mismatched password confirmation and supports password managers', async () => {
    const user = userEvent.setup();
    render(<ProfilePage />);
    await user.click(screen.getByRole('button', { name: 'Change Password' }));
    const current = screen.getByLabelText('Current Password');
    const password = screen.getByLabelText('New Password');
    const confirmation = screen.getByLabelText('Confirm New Password');
    expect(current).toHaveAttribute('autocomplete', 'current-password');
    expect(password).toHaveAttribute('autocomplete', 'new-password');
    await user.type(current, 'current-password');
    await user.type(password, 'new-password');
    await user.type(confirmation, 'different-password');
    await user.click(screen.getByRole('button', { name: 'Change Password' }));
    expect(confirmation).toHaveFocus();
    expect(confirmation).toHaveAccessibleDescription('Enter the same new password again.');
    expect(mutation).not.toHaveBeenCalled();
  });
});

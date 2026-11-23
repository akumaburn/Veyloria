import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import InventoryList from './InventoryList';

const state = vi.hoisted(() => ({
  items: [] as any[],
  canModify: true,
  canManage: false,
  use: vi.fn(),
  give: vi.fn(),
  equip: vi.fn(),
  remove: vi.fn(),
  add: vi.fn(),
  error: undefined as Error | undefined,
  refetch: vi.fn(),
}));
vi.mock('@/hooks/useRoleplay', () => ({
  useCharacterInventory: () => ({
    inventory: state.items,
    loading: false,
    error: state.error,
    refetch: state.refetch,
  }),
  useUserInventory: () => ({
    inventory: state.items,
    loading: false,
    error: state.error,
    refetch: state.refetch,
  }),
  useCharacterStats: () => ({ stats: [{ statName: 'HP', statType: 'numeric' }] }),
  useInventoryAccess: () => ({
    canModify: state.canModify,
    canManage: state.canManage,
    refetch: state.refetch,
    recipients: [
      { id: 'player-b', name: 'Bryn', kind: 'user' },
      { id: 'elara', name: 'Elara', kind: 'character', canReceiveEffects: true },
    ],
  }),
  useUseInventoryItem: () => ({ useInventoryItem: state.use }),
  useTransferInventoryItem: () => ({ transferInventoryItem: state.give }),
  useEquipItem: () => ({ equipItem: state.equip }),
  useRemoveInventoryItem: () => ({ removeInventoryItem: state.remove }),
  useAddInventoryItem: () => ({ addInventoryItem: state.add }),
}));
vi.mock('react-hot-toast', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
const potion = { inventoryId: 'potion', itemName: 'Potion', category: 'consumable', quantity: 3 };
beforeEach(() => {
  state.items = [potion];
  state.canModify = true;
  state.canManage = false;
  state.error = undefined;
  state.use.mockResolvedValue({});
  state.give.mockResolvedValue({});
  state.refetch.mockResolvedValue({});
  state.add.mockResolvedValue({});
});
it('exposes Use for consumables and Equip for equipment, including player inventory', () => {
  state.items = [
    potion,
    { inventoryId: 'sword', itemName: 'Sword', category: 'weapon', quantity: 1 },
  ];
  render(<InventoryList conversationId="game" userId="player" />);
  expect(screen.getByRole('button', { name: 'Use Potion' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Equip Sword' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Use Sword' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Equip Potion' })).not.toBeInTheDocument();
});
it('honors server permissions even when narrator mode is toggled', () => {
  state.canModify = false;
  render(<InventoryList conversationId="game" characterId="elara" narratorMode />);
  expect(screen.queryByRole('button', { name: 'Use Potion' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Give Potion' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Remove Potion' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Add Item' })).not.toBeInTheDocument();
});
it('guards repeated use while the first mutation is pending', async () => {
  let resolve!: (value: object) => void;
  state.use.mockReturnValue(
    new Promise((r) => {
      resolve = r;
    }),
  );
  render(<InventoryList conversationId="game" userId="player" />);
  fireEvent.click(screen.getByRole('button', { name: 'Use Potion' }));
  fireEvent.click(screen.getByRole('button', { name: 'Use Potion' }));
  expect(state.use).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('button', { name: 'Give Potion' })).toBeDisabled();
  resolve({});
  await waitFor(() => expect(screen.getByRole('button', { name: 'Use Potion' })).toBeEnabled());
});
it('gives the entire stack to a player with exactly one owner field', async () => {
  render(<InventoryList conversationId="game" userId="player" />);
  fireEvent.click(screen.getByRole('button', { name: 'Give Potion' }));
  expect(screen.getByText(/entire stack/)).toHaveTextContent('3 × Potion');
  fireEvent.change(screen.getByLabelText('Give item to'), { target: { value: 'user:player-b' } });
  fireEvent.click(screen.getByRole('button', { name: 'Confirm give' }));
  await waitFor(() => expect(state.give).toHaveBeenCalledWith('potion', undefined, 'player-b', 3));
});
it('requires confirmation to remove a whole stack', async () => {
  state.canManage = true;
  render(<InventoryList conversationId="game" userId="player" narratorMode />);
  fireEvent.click(screen.getByRole('button', { name: 'Remove Potion' }));
  expect(state.remove).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Confirm remove' }));
  await waitFor(() => expect(state.remove).toHaveBeenCalledWith('potion'));
});
it('shows retry on query failure instead of an empty inventory', () => {
  state.error = new Error('Offline');
  render(<InventoryList conversationId="game" userId="player" />);
  expect(screen.getByRole('alert')).toHaveTextContent('Inventory could not be loaded');
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(state.refetch).toHaveBeenCalled();
});
it('lets the host configure a consumable stat effect', async () => {
  state.canManage = true;
  render(<InventoryList conversationId="game" characterId="elara" narratorMode />);
  fireEvent.change(screen.getByLabelText('Item name'), { target: { value: 'Healing potion' } });
  fireEvent.change(screen.getByLabelText('Item category'), { target: { value: 'consumable' } });
  fireEvent.change(screen.getByLabelText('Affected stat'), { target: { value: 'HP' } });
  fireEvent.change(screen.getByLabelText('Stat change'), { target: { value: '15' } });
  fireEvent.click(screen.getByRole('button', { name: 'Add Item' }));
  await waitFor(() =>
    expect(state.add).toHaveBeenCalledWith(
      expect.objectContaining({
        characterId: 'elara',
        category: 'consumable',
        properties: JSON.stringify({ effects: [{ statName: 'HP', delta: 15 }] }),
      }),
    ),
  );
});

it('selects an authorized character when using a player-held potion with stat effects', async () => {
  state.items = [
    { ...potion, properties: JSON.stringify({ effects: [{ statName: 'HP', delta: 10 }] }) },
  ];
  render(<InventoryList conversationId="game" userId="player" />);
  fireEvent.click(screen.getByRole('button', { name: 'Use Potion' }));
  expect(state.use).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('Effect recipient'), { target: { value: 'elara' } });
  fireEvent.click(screen.getByRole('button', { name: 'Confirm use' }));
  await waitFor(() => expect(state.use).toHaveBeenCalledWith('potion', 'elara'));
});
it('supports giving part of a stack', async () => {
  render(<InventoryList conversationId="game" userId="player" />);
  fireEvent.click(screen.getByRole('button', { name: 'Give Potion' }));
  fireEvent.change(screen.getByLabelText('Give item to'), { target: { value: 'character:elara' } });
  fireEvent.change(screen.getByLabelText('Quantity to give'), { target: { value: '1' } });
  fireEvent.click(screen.getByRole('button', { name: 'Confirm give' }));
  await waitFor(() => expect(state.give).toHaveBeenCalledWith('potion', 'elara', undefined, 1));
});

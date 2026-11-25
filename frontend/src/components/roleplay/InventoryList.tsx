import { useState, useRef } from 'react';
import { cn } from '@/lib/utils';
import { type CharacterInventoryItem } from '@/graphql/types';
import {
  useCharacterInventory,
  useUserInventory,
  useAddInventoryItem,
  useRemoveInventoryItem,
  useEquipItem,
  useUseInventoryItem,
  useTransferInventoryItem,
  useInventoryAccess,
  useCharacterStats,
} from '@/hooks/useRoleplay';
import { Shield, Loader2, Plus, Trash2 } from 'lucide-react';
import { toast } from 'react-hot-toast';
import { useAuthStore } from '@/stores/authStore';

const ITEM_ACTION = 'inline-flex min-h-10 items-center rounded px-3 text-xs disabled:opacity-50 sm:min-h-0 sm:px-2 sm:py-1';

interface InventoryListProps {
  conversationId: string;
  characterId?: string;
  userId?: string;
  narratorMode?: boolean;
}

function AddItemForm({
  conversationId,
  characterId,
  userId,
}: {
  conversationId: string;
  characterId?: string;
  userId?: string;
}) {
  const { addInventoryItem, loading } = useAddInventoryItem();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState('');
  const [quantity, setQuantity] = useState(1);
  const [effectStat, setEffectStat] = useState('');
  const [slot, setSlot] = useState('');
  const [effectDelta, setEffectDelta] = useState(10);
  const { stats } = useCharacterStats(conversationId, characterId || '');

  const handleAdd = async () => {
    if (loading) return;
    if (!Number.isInteger(quantity) || quantity < 1) {
      toast.error('Quantity must be a positive whole number');
      return;
    }
    if (!name.trim()) {
      toast.error('Item name is required');
      return;
    }
    const properties: Record<string, unknown> = {};
    if (effectStat && category === 'consumable')
      properties.effects = [{ statName: effectStat, delta: effectDelta }];
    if (slot && ['weapon', 'armor', 'equipment', 'accessory'].includes(category))
      properties.slot = slot;
    try {
      await addInventoryItem({
        conversationId,
        characterId,
        userId,
        itemName: name.trim(),
        itemDescription: description.trim() || undefined,
        category: category.trim() || undefined,
        quantity,
        properties: Object.keys(properties).length ? JSON.stringify(properties) : undefined,
      });
      setName('');
      setDescription('');
      setCategory('');
      setQuantity(1);
      setEffectStat('');
      setSlot('');
      toast.success('Item added');
    } catch {
      toast.error('Failed to add item');
    }
  };

  return (
    <div className="border-border/60 bg-muted/40 mt-3 space-y-2 rounded-lg border border-dashed p-2.5">
      <h4 className="text-foreground text-sm font-medium">Add item</h4>
      <input
        type="text"
        aria-label="Item name"
        maxLength={255}
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="Item name"
        className={cn(
          'border-border bg-muted/50 w-full rounded-lg border px-2.5 py-1.5',
          'text-foreground placeholder-muted-foreground text-xs',
          'focus:border-primary focus:ring-primary/30 focus:ring-1 focus:outline-none',
        )}
        onKeyDown={(e) => e.key === 'Enter' && void handleAdd()}
      />
      <input
        type="text"
        aria-label="Item description"
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        placeholder="Description (optional)"
        className={cn(
          'border-border bg-muted/50 w-full rounded-lg border px-2.5 py-1.5',
          'text-foreground placeholder-muted-foreground text-xs',
          'focus:border-primary focus:ring-primary/30 focus:ring-1 focus:outline-none',
        )}
      />
      <div className="flex gap-2">
        {/* min-w-0: an input's intrinsic width would otherwise keep this
            flex item from shrinking and push the row past the panel edge */}
        <select
          aria-label="Item category"
          value={category}
          onChange={(e) => {
            setCategory(e.target.value);
            setEffectStat('');
          }}
          className={cn(
            'border-border bg-muted/50 min-w-0 flex-1 rounded-lg border px-2.5 py-1.5',
            'text-foreground placeholder-muted-foreground text-xs',
            'focus:border-primary focus:ring-primary/30 focus:ring-1 focus:outline-none',
          )}
        >
          <option value="">Choose category</option>
          {['weapon', 'armor', 'equipment', 'accessory', 'consumable', 'quest', 'misc'].map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
        <input
          type="number"
          min={1}
          step={1}
          aria-label="Item quantity"
          value={quantity}
          onChange={(e) => setQuantity(Number(e.target.value))}
          className={cn(
            'border-border bg-muted/50 w-16 flex-shrink-0 rounded-lg border px-2.5 py-1.5',
            'text-foreground text-xs',
            'focus:border-primary focus:ring-primary/30 focus:ring-1 focus:outline-none',
          )}
        />
      </div>
      {['weapon', 'armor', 'equipment', 'accessory'].includes(category) && (
        <label className="text-muted-foreground block text-xs">
          Equipment slot
          <select
            aria-label="Equipment slot"
            value={slot}
            onChange={(e) => setSlot(e.target.value)}
            className="border-border bg-background text-foreground mt-1 w-full rounded-lg border px-2 py-1.5"
          >
            <option value="">No slot restriction</option>
            {Object.entries({
              main_hand: 'Main hand',
              off_hand: 'Off hand',
              head: 'Head',
              body: 'Body',
              hands: 'Hands',
              legs: 'Legs',
              feet: 'Feet',
              neck: 'Neck',
              finger: 'Finger',
            }).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      )}
      {characterId && category === 'consumable' && (
        <div className="space-y-2">
          <label className="text-muted-foreground block text-xs">
            Effect on use
            <select
              aria-label="Affected stat"
              value={effectStat}
              onChange={(e) => setEffectStat(e.target.value)}
              className="border-border bg-background text-foreground mt-1 w-full rounded-lg border px-2 py-1.5"
            >
              <option value="">Story use only</option>
              {stats
                .filter((stat: { statType?: string }) => stat.statType === 'numeric')
                .map((stat: { statName: string }) => (
                  <option key={stat.statName} value={stat.statName}>
                    {stat.statName}
                  </option>
                ))}
            </select>
          </label>
          {effectStat && (
            <label className="text-muted-foreground block text-xs">
              Amount restored (negative to reduce)
              <input
                type="number"
                aria-label="Stat change"
                value={effectDelta}
                onChange={(e) => setEffectDelta(Number(e.target.value))}
                className="border-border bg-background text-foreground mt-1 w-full rounded-lg border px-2 py-1.5"
              />
            </label>
          )}
        </div>
      )}
      <button
        onClick={() => void handleAdd()}
        disabled={
          loading ||
          !name.trim() ||
          !Number.isInteger(quantity) ||
          quantity < 1 ||
          !Number.isFinite(effectDelta)
        }
        className={cn(
          'inline-flex min-h-10 w-full items-center justify-center gap-1.5 rounded-lg px-2.5 py-1.5 sm:min-h-0',
          'bg-primary/10 text-primary text-xs font-medium',
          'hover:bg-primary/20 transition-colors disabled:opacity-50',
        )}
      >
        {loading ? <Loader2 size={12} className="animate-spin" /> : <Plus size={12} />}
        Add Item
      </button>
    </div>
  );
}

export default function InventoryList({
  conversationId,
  characterId,
  userId,
  narratorMode,
}: InventoryListProps) {
  const charResult = useCharacterInventory(conversationId, characterId || '');
  const userResult = useUserInventory(conversationId, userId || '');
  const { removeInventoryItem, loading: removingItem } = useRemoveInventoryItem();
  const { equipItem } = useEquipItem();
  const { useInventoryItem: applyItem } = useUseInventoryItem();
  const { transferInventoryItem } = useTransferInventoryItem();
  const access = useInventoryAccess(conversationId, characterId, userId);
  const [busyId, setBusyId] = useState<string | null>(null);
  const busyRef = useRef(false);
  const [givingId, setGivingId] = useState<string | null>(null);
  const [recipient, setRecipient] = useState('');
  const [giveQuantity, setGiveQuantity] = useState(1);
  const [usingId, setUsingId] = useState<string | null>(null);
  const [effectTarget, setEffectTarget] = useState('');
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const currentUserId = useAuthStore((state) => state.user?.id);
  const isOwnInventory = !characterId && !!userId && userId === currentUserId;
  // Outside narrator mode the player acts only on their own pack; other
  // inventories change through the story, where the accountant agent applies them.
  const canModify = access.canModify === true && (narratorMode === true || isOwnInventory);
  const requestInStory = !canModify && !isOwnInventory && !narratorMode;
  const canManage = narratorMode && access.canManage === true;

  const inventory = characterId ? charResult.inventory : userResult.inventory;
  const loading = characterId ? charResult.loading : userResult.loading;

  // Group items by category
  const grouped = (inventory as CharacterInventoryItem[]).reduce<
    Record<string, CharacterInventoryItem[]>
  >((acc, item) => {
    const category = item.category || 'Uncategorized';
    if (!acc[category]) acc[category] = [];
    acc[category].push(item);
    return acc;
  }, Object.create(null));

  const sortedCategories = Object.keys(grouped).sort((a, b) => {
    if (a === 'Uncategorized') return 1;
    if (b === 'Uncategorized') return -1;
    return a.localeCompare(b);
  });

  const act = async (id: string, action: () => Promise<unknown>, success: string) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusyId(id);
    try {
      await action();
      setGivingId(null);
      setUsingId(null);
      setConfirmRemove(null);
      toast.success(success);
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : 'Inventory action failed. Please retry.',
      );
      void access.refetch();
    } finally {
      busyRef.current = false;
      setBusyId(null);
    }
  };

  const result = characterId ? charResult : userResult;
  if (result.error || access.error)
    return (
      <div role="alert" className="text-destructive space-y-2 text-sm">
        <p>Inventory could not be loaded.</p>
        <button
          type="button"
          onClick={() => {
            void result.refetch();
            void access.refetch();
          }}
          className="underline"
        >
          Retry
        </button>
      </div>
    );

  if (loading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="text-muted-foreground h-5 w-5 animate-spin" />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {/* Empty state: one line, no icon tower — the section header already
          names what is missing, so the body just confirms it. */}
      {sortedCategories.length === 0 && (
        <p className="text-muted-foreground py-1 text-center text-sm">No items in inventory</p>
      )}

      {/* Grouped items */}
      {sortedCategories.map((category) => (
        <div key={category}>
          <h4 className="text-muted-foreground mb-2 text-xs font-semibold tracking-wider uppercase">
            {category}
          </h4>
          <div className="flex flex-col gap-1.5">
            {grouped[category].map((item) => {
              const rules = itemRules(item);
              return (
                <div
                  key={item.inventoryId}
                  className={cn(
                    'group border-border/60 bg-muted/40 flex flex-wrap items-start gap-3 rounded-lg border px-3 py-2.5 transition-colors',
                    item.isEquipped && 'border-warning/40 bg-warning/5',
                  )}
                >
                  {/* Equipped indicator */}
                  {item.isEquipped && (
                    <div className="bg-warning/15 flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full">
                      <Shield size={12} className="text-warning" />
                    </div>
                  )}

                  {/* Item info */}
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-foreground truncate text-sm font-medium">
                        {item.itemName}
                      </span>
                      {(item.quantity ?? 1) > 1 && (
                        <span className="bg-muted text-muted-foreground flex-shrink-0 rounded-md px-1.5 py-0.5 font-mono text-xs">
                          x{item.quantity}
                        </span>
                      )}
                    </div>
                    {item.itemDescription && (
                      <p className="text-muted-foreground mt-0.5 line-clamp-2 text-xs">
                        {item.itemDescription}
                      </p>
                    )}
                  </div>

                  {canModify && (
                    <div className="flex w-full flex-wrap items-center justify-end gap-1">
                      {(rules.equippable || item.isEquipped) && (
                        <button
                          type="button"
                          disabled={!!busyId}
                          onClick={() =>
                            void act(
                              item.inventoryId,
                              () => equipItem(item.inventoryId, !item.isEquipped),
                              item.isEquipped ? 'Item unequipped' : 'Item equipped',
                            )
                          }
                          className={cn(ITEM_ACTION, 'text-muted-foreground hover:bg-muted hover:text-foreground')}
                          aria-label={`${item.isEquipped ? 'Unequip' : 'Equip'} ${item.itemName}`}
                        >
                          {item.isEquipped ? 'Unequip' : 'Equip'}
                        </button>
                      )}
                      {rules.usable && (
                        <button
                          type="button"
                          disabled={!!busyId}
                          onClick={() => {
                            if (rules.hasEffects && !characterId) {
                              setUsingId(item.inventoryId);
                              setEffectTarget('');
                            } else
                              void act(
                                item.inventoryId,
                                () => applyItem(item.inventoryId),
                                rules.consumable ? 'Used one item' : 'Item used',
                              );
                          }}
                          className={cn(ITEM_ACTION, 'text-primary hover:bg-primary/10')}
                          aria-label={`Use ${item.itemName}`}
                        >
                          Use
                        </button>
                      )}
                      {rules.transferable && !!access.recipients?.length && (
                        <button
                          type="button"
                          disabled={!!busyId}
                          onClick={() => {
                            setGivingId(givingId === item.inventoryId ? null : item.inventoryId);
                            setRecipient('');
                            setGiveQuantity(item.quantity ?? 1);
                          }}
                          className={cn(ITEM_ACTION, 'text-muted-foreground hover:bg-muted hover:text-foreground')}
                          aria-label={`Give ${item.itemName}`}
                        >
                          Give
                        </button>
                      )}
                      {canManage && (
                        <button
                          type="button"
                          onClick={() => setConfirmRemove(item.inventoryId)}
                          disabled={!!busyId || removingItem}
                          title="Remove entire stack"
                          aria-label={`Remove ${item.itemName}`}
                          className="text-muted-foreground hover:bg-destructive/10 hover:text-destructive inline-flex h-10 w-10 items-center justify-center rounded disabled:opacity-50 sm:h-7 sm:w-7"
                        >
                          <Trash2 size={14} />
                        </button>
                      )}
                      {busyId === item.inventoryId && (
                        <Loader2
                          size={14}
                          aria-label="Updating inventory"
                          className="animate-spin"
                        />
                      )}
                    </div>
                  )}

                  {usingId === item.inventoryId && (
                    <div className="flex w-full flex-wrap items-center gap-2 pt-2">
                      <label className="text-muted-foreground w-full text-xs">
                        {access.recipients?.some((r) => r.canReceiveEffects)
                          ? 'Choose a character to receive the item’s effect.'
                          : 'No characters you control are available. Give the item to its recipient instead.'}
                      </label>
                      <select
                        aria-label="Effect recipient"
                        value={effectTarget}
                        onChange={(e) => setEffectTarget(e.target.value)}
                        disabled={!!busyId}
                        className="border-border bg-background w-full rounded border px-2 py-2 text-xs sm:py-1"
                      >
                        <option value="">Choose character</option>
                        {access.recipients
                          ?.filter((r) => r.kind === 'character' && r.canReceiveEffects)
                          .map((r) => (
                            <option key={r.id} value={r.id}>
                              {r.name}
                            </option>
                          ))}
                      </select>
                      <button
                        type="button"
                        disabled={!effectTarget || !!busyId}
                        onClick={() =>
                          void act(
                            item.inventoryId,
                            () => applyItem(item.inventoryId, effectTarget),
                            'Item used',
                          )
                        }
                        className={cn(ITEM_ACTION, 'text-primary hover:bg-primary/10')}
                      >
                        Confirm use
                      </button>
                      <button
                        type="button"
                        disabled={!!busyId}
                        onClick={() => setUsingId(null)}
                        className={cn(ITEM_ACTION, 'text-muted-foreground hover:bg-muted')}
                      >
                        Cancel
                      </button>
                    </div>
                  )}
                  {givingId === item.inventoryId && (
                    <div className="flex w-full flex-wrap items-center gap-2 pt-2">
                      <label className="text-muted-foreground w-full text-xs">
                        Give {giveQuantity} × {item.itemName}
                        {giveQuantity === (item.quantity ?? 1) ? ' (entire stack)' : ''}
                      </label>
                      <select
                        aria-label="Give item to"
                        value={recipient}
                        onChange={(e) => setRecipient(e.target.value)}
                        disabled={!!busyId}
                        className="border-border bg-background w-full rounded border px-2 py-2 text-xs sm:py-1"
                      >
                        <option value="">Choose recipient</option>
                        {access.recipients?.map((r) => (
                          <option key={`${r.kind}:${r.id}`} value={`${r.kind}:${r.id}`}>
                            {r.name} ({r.kind === 'user' ? 'player' : 'character'})
                          </option>
                        ))}
                      </select>
                      {(item.quantity ?? 1) > 1 && (
                        <input
                          type="number"
                          min={1}
                          max={item.quantity}
                          step={1}
                          value={giveQuantity}
                          aria-label="Quantity to give"
                          disabled={!!busyId}
                          onChange={(e) => setGiveQuantity(Number(e.target.value))}
                          className="border-border bg-background w-16 rounded border px-2 py-2 text-xs sm:py-1"
                        />
                      )}
                      <button
                        type="button"
                        disabled={
                          !recipient ||
                          !!busyId ||
                          !Number.isInteger(giveQuantity) ||
                          giveQuantity < 1 ||
                          giveQuantity > (item.quantity ?? 1)
                        }
                        className={cn(ITEM_ACTION, 'text-primary hover:bg-primary/10')}
                        onClick={() => {
                          const target = access.recipients?.find(
                            (r) => `${r.kind}:${r.id}` === recipient,
                          );
                          if (target)
                            void act(
                              item.inventoryId,
                              () =>
                                transferInventoryItem(
                                  item.inventoryId,
                                  target.kind === 'character' ? target.id : undefined,
                                  target.kind === 'user' ? target.id : undefined,
                                  giveQuantity,
                                ),
                              `Gave item to ${target.name}`,
                            );
                        }}
                      >
                        Confirm give
                      </button>
                      <button
                        type="button"
                        disabled={!!busyId}
                        onClick={() => setGivingId(null)}
                        className={cn(ITEM_ACTION, 'text-muted-foreground hover:bg-muted')}
                      >
                        Cancel
                      </button>
                    </div>
                  )}
                  {canManage && confirmRemove === item.inventoryId && (
                    <div className="flex w-full flex-wrap items-center gap-2 pt-2 text-xs">
                      <span className="w-full break-words sm:w-auto">
                        Remove all {item.quantity ?? 1} × {item.itemName}?
                      </span>
                      <button
                        type="button"
                        disabled={!!busyId}
                        className={cn(ITEM_ACTION, 'text-destructive hover:bg-destructive/10')}
                        onClick={() =>
                          void act(
                            item.inventoryId,
                            () => removeInventoryItem(item.inventoryId),
                            'Item removed',
                          )
                        }
                      >
                        Confirm remove
                      </button>
                      <button
                        type="button"
                        disabled={!!busyId}
                        onClick={() => setConfirmRemove(null)}
                        className={cn(ITEM_ACTION, 'text-muted-foreground hover:bg-muted')}
                      >
                        Cancel
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      ))}

      {requestInStory && sortedCategories.length > 0 && (
        <p className="text-muted-foreground text-xs">
          Ask for these items in the story to use or trade them.
        </p>
      )}

      {/* Narrator mode: add item form */}
      {canManage && (
        <AddItemForm conversationId={conversationId} characterId={characterId} userId={userId} />
      )}
    </div>
  );
}

export function itemRules(item: CharacterInventoryItem) {
  let props: Record<string, unknown> = {};
  try {
    props = JSON.parse(item.properties || '{}') || {};
  } catch {
    /* Legacy items may lack metadata. */
  }
  const consumable =
    typeof props.consumable === 'boolean'
      ? props.consumable
      : item.category?.toLowerCase() === 'consumable';
  const equippable =
    !consumable &&
    (typeof props.equippable === 'boolean'
      ? props.equippable
      : ['weapon', 'armor', 'equipment', 'accessory', 'shield'].includes(
          item.category?.toLowerCase() || '',
        ));
  return {
    consumable,
    equippable,
    transferable: props.transferable !== false,
    hasEffects: Array.isArray(props.effects) && props.effects.length > 0,
    usable: consumable || props.usable === true,
  };
}

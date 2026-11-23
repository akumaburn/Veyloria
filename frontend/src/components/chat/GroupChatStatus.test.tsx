import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { GroupChatHeader } from './GroupChatHeader';
describe('group story progress', () => {
  it('shows narrator work instead of attributing it to the last character', () => {
    const noop=vi.fn();
    const { rerender }=render(<GroupChatHeader title="Dungeon Diving" participants={[]} currentSpeaker={{ id:'elara',name:'Elara' } as any}
      processingLabel="Narrator is writing the scene…" groupState="CHARACTER_RESPONDING" turnMode="ROUND_ROBIN"
      onPause={noop} onResume={noop} onRequestInterjection={noop} onTriggerNextCharacter={noop} onStop={noop} onOpenSettings={noop} />);
    expect(screen.getByText('Narrator is writing the scene…')).toBeInTheDocument();
    expect(screen.queryByText(/Elara is/)).not.toBeInTheDocument();
    rerender(<GroupChatHeader title="Dungeon Diving" participants={[]} currentSpeaker={null}
      groupState="CHARACTER_RESPONDING" turnMode="ROUND_ROBIN"
      onPause={noop} onResume={noop} onRequestInterjection={noop} onTriggerNextCharacter={noop} onStop={noop} onOpenSettings={noop} />);
    expect(screen.getByText('Resolving the story…')).toBeInTheDocument();
    expect(screen.queryByText(/Elara is/)).not.toBeInTheDocument();
  });
});

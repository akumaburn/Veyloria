import { useParties, useLeaveParty } from '@/hooks/useRoleplay';
import { type Party, type PartyMember } from '@/graphql/types';
import { Button } from '@/components/ui/Button';
import { Users, Crown, Copy, LogOut, Loader2, UserCircle } from 'lucide-react';
import { useState } from 'react';

interface PartyPanelProps {
  partyId?: string;
  onCreateParty?: () => void;
  onJoinParty?: () => void;
}

export default function PartyPanel({ partyId, onCreateParty, onJoinParty }: PartyPanelProps) {
  const { parties, loading } = useParties();
  const { leaveParty, loading: leaving } = useLeaveParty();
  const [copied, setCopied] = useState(false);

  const party = partyId
    ? parties.find((p: Party) => p.partyId === partyId)
    : parties[0];

  const handleCopyCode = async () => {
    if (!party?.inviteCode) return;
    await navigator.clipboard.writeText(party.inviteCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleLeave = () => {
    const id = partyId ?? party?.partyId;
    if (id) leaveParty(id);
  };

  if (loading) {
    return (
      <div className="flex h-48 items-center justify-center rounded-2xl border border-border bg-card">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!party) {
    return (
      <div className="border-border bg-card rounded-2xl border px-6 py-8 text-center">
        <div className="bg-primary/10 text-primary mx-auto mb-3 flex h-10 w-10 items-center justify-center rounded-2xl">
          <Users className="h-5 w-5" aria-hidden="true" />
        </div>
        <h2 className="text-foreground text-base font-semibold">No party yet</h2>
        <p className="text-muted-foreground mx-auto mt-1 max-w-sm text-sm">
          A party shares one world and one transcript. Create one for your group, or join with an
          invite code.
        </p>
        <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
          {onCreateParty && (
            <Button variant="primary" onClick={onCreateParty}>
              Create a party
            </Button>
          )}
          {onJoinParty && (
            <Button variant="outline" onClick={onJoinParty}>
              Join with a code
            </Button>
          )}
        </div>
      </div>
    );
  }

  const members = party.members ?? [];

  return (
    <div className="rounded-2xl border border-border bg-card">
      {/* Header */}
      <div className="border-b border-border p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            <Users size={16} className="shrink-0 text-primary" />
            <h3 className="truncate text-sm font-semibold text-foreground">{party.name}</h3>
          </div>
          <span className="shrink-0 text-xs text-muted-foreground">
            {members.length}/{party.maxMembers ?? '?'} members
          </span>
        </div>
        {party.description && (
          <p className="mt-1 text-xs text-muted-foreground">{party.description}</p>
        )}
      </div>

      {/* Members list — PartyMember exposes only scalar IDs, so we show the
          identifiers directly. Join against characters/users in the caller if
          richer display is needed. */}
      <div className="divide-y divide-border/60">
        {members.map((member: PartyMember) => {
          const isLeader = member.userId === party.leaderId;
          return (
            <div key={member.id} className="flex items-center gap-3 px-4 py-2.5">
              <UserCircle size={32} className="text-muted-foreground/70" />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="truncate font-mono text-xs text-foreground">
                    {member.userId.slice(0, 8)}
                  </span>
                  {isLeader && <Crown size={12} className="flex-shrink-0 text-warning" />}
                </div>
                {member.characterId && (
                  <p className="truncate font-mono text-[10px] text-muted-foreground">
                    character {member.characterId.slice(0, 8)}
                  </p>
                )}
              </div>
              {member.role && (
                <span className="rounded-full bg-accent/60 px-2 py-0.5 text-[10px] text-muted-foreground">
                  {member.role}
                </span>
              )}
            </div>
          );
        })}
      </div>

      {/* Footer actions */}
      <div className="border-t border-border p-3">
        {party.inviteCode && (
          <div className="mb-3 flex items-center gap-2 rounded-md bg-muted/50 px-3 py-2">
            <span className="min-w-0 flex-1 truncate font-mono text-xs text-foreground">{party.inviteCode}</span>
            <Button size="sm" variant="ghost" className="h-10 sm:h-8" onClick={handleCopyCode}>
              <Copy size={12} />
              <span className="ml-1 text-xs">{copied ? 'Copied' : 'Copy'}</span>
            </Button>
          </div>
        )}
        <Button
          variant="danger"
          size="sm"
          className="h-10 w-full sm:h-8"
          onClick={handleLeave}
          isLoading={leaving}
          leftIcon={<LogOut size={14} />}
        >
          Leave Party
        </Button>
      </div>
    </div>
  );
}

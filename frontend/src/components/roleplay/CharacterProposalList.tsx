import { useEffect } from 'react';
import {
  useCharacterProposals,
  useApproveCharacterProposal,
  useRejectCharacterProposal,
  useCharacterProposalSubscription,
} from '@/hooks/useRoleplay';
import CharacterProposalCard from './CharacterProposalCard';
import { UserPlus } from 'lucide-react';

interface CharacterProposalListProps {
  conversationId: string;
}

export default function CharacterProposalList({
  conversationId,
}: CharacterProposalListProps) {
  const { proposals, loading, refetch } = useCharacterProposals(conversationId);
  const { approveProposal, loading: approveLoading } = useApproveCharacterProposal();
  const { rejectProposal, loading: rejectLoading } = useRejectCharacterProposal();
  const { proposal: newProposal } = useCharacterProposalSubscription(conversationId);

  // Refetch when a new proposal arrives via subscription
  useEffect(() => {
    if (newProposal) {
      refetch();
    }
  }, [newProposal, refetch]);

  const pendingCount = proposals?.filter((p: any) => p.status === 'PENDING').length ?? 0;
  const heading = (
    <div className="flex flex-wrap items-center gap-2">
      <UserPlus size={16} className="shrink-0 text-primary" />
      <h3 className="text-sm font-semibold text-foreground uppercase tracking-wider">
        Character Proposals
      </h3>
      {pendingCount > 0 && (
        <span className="rounded-full bg-warning/15 px-2 py-0.5 text-xs text-warning">
          {pendingCount} pending
        </span>
      )}
    </div>
  );

  if (loading) {
    return (
      <div className="flex flex-col gap-4">
        {heading}
        <div className="flex items-center justify-center rounded-xl border border-border bg-card p-8">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-input border-t-foreground" />
          <span className="ml-3 text-sm text-muted-foreground">Loading proposals...</span>
        </div>
      </div>
    );
  }

  if (!proposals || proposals.length === 0) {
    return (
      <div className="flex flex-col gap-4">
        {heading}
        <div className="rounded-xl border border-border bg-card p-6 text-center">
          <UserPlus size={24} className="mx-auto mb-2 text-muted-foreground/70" />
          <p className="text-sm text-muted-foreground">
            No character proposals yet. The Casting Director will suggest new characters
            when the story calls for them.
          </p>
        </div>
      </div>
    );
  }

  const handleApprove = async (proposalId: string) => {
    await approveProposal(proposalId);
  };

  const handleReject = async (proposalId: string) => {
    await rejectProposal(proposalId);
  };

  // Show pending first, then others
  const sorted = [...proposals].sort((a, b) => {
    if (a.status === 'PENDING' && b.status !== 'PENDING') return -1;
    if (a.status !== 'PENDING' && b.status === 'PENDING') return 1;
    return 0;
  });

  return (
    <div className="flex flex-col gap-4">
      {heading}
      {sorted.map((proposal) => (
        <CharacterProposalCard
          key={proposal.proposalId}
          proposal={proposal}
          onApprove={handleApprove}
          onReject={handleReject}
          approveLoading={approveLoading}
          rejectLoading={rejectLoading}
        />
      ))}
    </div>
  );
}

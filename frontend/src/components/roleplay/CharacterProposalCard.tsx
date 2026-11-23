import { cn } from '@/lib/utils';
import { type CharacterProposal } from '@/graphql/types';
import { Button } from '@/components/ui/Button';
import {
  UserPlus,
  Check,
  X,
  Sparkles,
  BookOpen,
  Target,
  Eye,
  Brain,
  Shield,
  Calculator,
} from 'lucide-react';

interface CharacterProposalCardProps {
  proposal: CharacterProposal;
  onApprove: (proposalId: string) => void;
  onReject: (proposalId: string) => void;
  approveLoading?: boolean;
  rejectLoading?: boolean;
}

function StatusBadge({ status }: { status: string }) {
  const styles: Record<string, string> = {
    PENDING: 'bg-warning/15 text-warning ring-warning/40',
    APPROVED: 'bg-success/15 text-success ring-success/40',
    REJECTED: 'bg-destructive/15 text-destructive ring-destructive/40',
    FAILED: 'bg-muted text-muted-foreground ring-border',
  };

  return (
    <span
      className={cn(
        'rounded-full px-2 py-0.5 text-xs font-medium ring-1',
        styles[status] ?? styles.FAILED
      )}
    >
      {status}
    </span>
  );
}

function Section({
  icon,
  title,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg bg-muted/40 p-3">
      <div className="mb-1.5 flex items-center gap-1.5">
        {icon}
        <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {title}
        </span>
      </div>
      <p className="text-sm text-foreground leading-relaxed">{children}</p>
    </div>
  );
}

export default function CharacterProposalCard({
  proposal,
  onApprove,
  onReject,
  approveLoading,
  rejectLoading,
}: CharacterProposalCardProps) {
  const isPending = proposal.status === 'PENDING';

  return (
    <div className="rounded-xl border border-border/60 bg-card p-5">
      {/* Header */}
      <div className="mb-4 flex items-start justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-purple-500/15">
            <UserPlus size={20} className="text-primary" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-foreground">
              {proposal.proposedName || 'Unnamed Character'}
            </h3>
            {proposal.proposedDescription && (
              <p className="text-sm text-muted-foreground">{proposal.proposedDescription}</p>
            )}
          </div>
        </div>
        <StatusBadge status={proposal.status} />
      </div>

      {/* Character Details */}
      <div className="mb-4 flex flex-col gap-3">
        {proposal.proposedPersonality && (
          <Section
            icon={<Brain size={14} className="text-primary" />}
            title="Personality"
          >
            {proposal.proposedPersonality}
          </Section>
        )}

        {proposal.proposedAppearance && (
          <Section
            icon={<Eye size={14} className="text-primary" />}
            title="Appearance"
          >
            {proposal.proposedAppearance}
          </Section>
        )}

        {proposal.proposedBackstory && (
          <Section
            icon={<BookOpen size={14} className="text-warning" />}
            title="Backstory"
          >
            {proposal.proposedBackstory}
          </Section>
        )}

        {proposal.proposedGoals && (
          <Section
            icon={<Target size={14} className="text-success" />}
            title="Goals"
          >
            {proposal.proposedGoals}
          </Section>
        )}

        {proposal.proposedGreeting && (
          <Section
            icon={<Sparkles size={14} className="text-primary" />}
            title="Greeting"
          >
            <em>&ldquo;{proposal.proposedGreeting}&rdquo;</em>
          </Section>
        )}
      </div>

      {/* Review Summaries */}
      {(proposal.accountantReview || proposal.skepticReview) && (
        <div className="mb-4 flex gap-3">
          {proposal.accountantReview && (
            <div className="flex-1 rounded-lg bg-muted/60 p-2.5">
              <div className="mb-1 flex items-center gap-1">
                <Calculator size={12} className="text-success" />
                <span className="text-xs font-medium text-muted-foreground">Accountant</span>
              </div>
              <p className="text-xs text-muted-foreground line-clamp-3">
                {proposal.accountantReview}
              </p>
            </div>
          )}
          {proposal.skepticReview && (
            <div className="flex-1 rounded-lg bg-muted/60 p-2.5">
              <div className="mb-1 flex items-center gap-1">
                <Shield size={12} className="text-destructive" />
                <span className="text-xs font-medium text-muted-foreground">Skeptic</span>
              </div>
              <p className="text-xs text-muted-foreground line-clamp-3">
                {proposal.skepticReview}
              </p>
            </div>
          )}
        </div>
      )}

      {/* Actions */}
      {isPending && (
        <div className="flex items-center gap-2 border-t border-border/60 pt-4">
          <Button
            variant="primary"
            size="sm"
            onClick={() => onApprove(proposal.proposalId)}
            isLoading={approveLoading}
            disabled={rejectLoading}
            leftIcon={<Check size={14} />}
          >
            Approve & Add to Story
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onReject(proposal.proposalId)}
            isLoading={rejectLoading}
            disabled={approveLoading}
            leftIcon={<X size={14} />}
          >
            Reject
          </Button>
        </div>
      )}

      {/* Casting prompt (what triggered this) */}
      {proposal.castingPrompt && (
        <div className="mt-3 border-t border-border/60 pt-3">
          <span className="text-xs text-muted-foreground">
            Triggered by: <em>&ldquo;{proposal.castingPrompt}&rdquo;</em>
          </span>
        </div>
      )}
    </div>
  );
}

import { useMemo } from 'react';
import { gql, useQuery } from '@apollo/client';
import { print } from 'graphql';
import { cn } from '@/lib/utils';
import { useWebSocketSubscription } from '@/lib/websocket-client';
import { GROUP_STATE_CHANGED_SUBSCRIPTION } from '@/graphql/operations';
import { latestProgress } from '@/services/conversationProgress';
import { PowerOff } from 'lucide-react';

type AgentStatus = 'idle' | 'processing' | 'complete' | 'error';
interface AgentPipelineStatusProps {
  conversationId: string;
  pipelineEnabled: boolean;
  agentStatuses?: Record<string, AgentStatus>;
}
const PROGRESS_QUERY = gql`query ConversationProcessing($id: String!) {
  conversation(id: $id) { id processingStatus { conversationId runId sequence stage label active updatedAt } }
}`;
const STATUS_SUBSCRIPTION = print(GROUP_STATE_CHANGED_SUBSCRIPTION);
const STEPS = [
  ['highlighter', 'Conversation'], ['encounter_decision', 'Encounters'], ['travel_decision', 'Travel'],
  ['interaction_decision', 'Interactions'], ['accountant', 'State'], ['residents', 'Locals'],
  ['narrator', 'Narration'], ['skeptic_narration', 'Review'], ['saving', 'Save'], ['greeting', 'Greeting'],
] as const;

export default function AgentPipelineStatus({ conversationId, pipelineEnabled, agentStatuses = {} }: AgentPipelineStatusProps) {
  const { data } = useQuery(PROGRESS_QUERY, { variables: { id: conversationId }, skip: !conversationId || !pipelineEnabled, fetchPolicy: 'cache-and-network' });
  const { data: event } = useWebSocketSubscription(STATUS_SUBSCRIPTION, { conversationId }, !conversationId || !pipelineEnabled);
  const progress = useMemo(() => {
    const frame = event?.groupStateChanged?.id === conversationId ? event.groupStateChanged : null;
    if (frame && !frame.processingOnly && (frame.currentSpeaker || ['WAITING_FOR_USER', 'PAUSED'].includes(frame.groupState))) return null;
    return latestProgress(data?.conversation?.processingStatus, frame?.processingStatus);
  }, [data, event, conversationId]);
  const active = progress?.active && progress.conversationId === conversationId;
  const stage = active ? progress.stage.startsWith('accountant') ? 'accountant' : progress.stage === 'movement' ? 'travel_decision' : progress.stage === 'review' ? 'skeptic_narration' : progress.stage : null;

  if (!pipelineEnabled) return <p className="flex items-center gap-2 px-3 py-2 text-xs text-muted-foreground"><PowerOff size={14} aria-hidden="true" />Story processing is disabled</p>;
  return <section className="space-y-3" aria-label="Story processing">
    <p role="status" className="text-sm text-foreground">{active ? progress.label : 'Ready for the next turn'}</p>
    <div className="flex flex-wrap gap-2">
      {STEPS.map(([id, label]) => {
        const status = stage === id ? 'processing' : agentStatuses[id] ?? 'idle';
        return <span key={id} title={`${label}: ${status}`} aria-current={stage === id ? 'step' : undefined}
          className={cn('rounded-md px-2 py-1 text-xs', status === 'processing' ? 'bg-primary/10 font-medium text-primary' : status === 'error' ? 'text-destructive' : status === 'complete' ? 'text-success' : 'text-muted-foreground')}>
          {label}
        </span>;
      })}
    </div>
  </section>;
}

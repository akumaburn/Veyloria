import { useNarratorMode, useNarratorModeStore } from '@/stores/narratorModeStore';
import { latestProgress, type ConversationProgress } from '@/services/conversationProgress';
import JourneyNotice from '@/components/roleplay/JourneyNotice';
import EncounterNotice from '@/components/roleplay/EncounterNotice';
import { useState, useContext, useRef, useEffect, useCallback, useMemo, lazy, Suspense } from 'react';
import { createPortal } from 'react-dom';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { ChatToolbarContext } from '@/components/layout/ChatToolbarContext';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import { Settings, Bot, Users, Volume2, MoreHorizontal, Check } from 'lucide-react';
import { useQuery, useMutation } from '@apollo/client';
import { gql } from '@apollo/client';
import { print } from 'graphql';
import { useWebSocketSubscription } from '@/lib/websocket-client';
import { toast } from 'react-hot-toast';
import { ChatInput } from './ChatInput';
import { MessageList } from './MessageList';
import { CharacterSelector, type SelectedCharacter } from './CharacterSelector';
import { ConversationSettings } from './ConversationSettings';
import { TypingIndicator } from './TypingIndicator';
import { GroupConversationCreator } from './GroupConversationCreator';
import { GroupChatHeader, type RoleplayHeaderControls } from './GroupChatHeader';
import { InterjectionModal } from './InterjectionModal';
import { ParticipantList } from './ParticipantList';
import { InviteNotificationBadge } from './InviteNotifications';
import SceneIndicator from '@/components/roleplay/SceneIndicator';
import { useAchievementUnlocked } from '@/hooks/useRoleplay';
import { cn } from '@/lib/utils';
import { useSendMessage, useSendGroupMessage, useSendTypingIndicator, useSwitchCharacter, useStopGeneration, useConversationParticipants } from '@/hooks/useGraphQL';
import { useConversationStore } from '@/stores/conversationStore';
import { useAuthStore } from '@/stores/authStore';
import { useTTSStore } from '@/stores/ttsStore';
import {
  REQUEST_INTERJECTION_MUTATION,
  PAUSE_GROUP_CONVERSATION_MUTATION,
  RESUME_GROUP_CONVERSATION_MUTATION,
  TRIGGER_NEXT_CHARACTER_RESPONSE_MUTATION,
  GROUP_STATE_CHANGED_SUBSCRIPTION,
  TURN_CHANGED_SUBSCRIPTION,
  INTERJECTION_REQUESTED_SUBSCRIPTION,
  INTERJECTION_RESOLVED_SUBSCRIPTION,
  CHARACTER_JOINED_SUBSCRIPTION,
} from '@/graphql/operations';

const RoleplayToolPanel = lazy(() => import('@/components/roleplay/RoleplayToolPanel'));
const RoleplayInitScreen = lazy(() => import('@/components/roleplay/RoleplayInitScreen'));
const AchievementPopup = lazy(() => import('@/components/roleplay/AchievementPopup'));

// Pre-print subscription queries for the custom WebSocket client
const GROUP_STATE_CHANGED_QUERY = print(GROUP_STATE_CHANGED_SUBSCRIPTION);
const TURN_CHANGED_QUERY = print(TURN_CHANGED_SUBSCRIPTION);
const INTERJECTION_REQUESTED_QUERY = print(INTERJECTION_REQUESTED_SUBSCRIPTION);
const INTERJECTION_RESOLVED_QUERY = print(INTERJECTION_RESOLVED_SUBSCRIPTION);
const CHARACTER_JOINED_QUERY = print(CHARACTER_JOINED_SUBSCRIPTION);

// Utility function for waiting with exponential backoff
const waitForCondition = async (
  checkFn: () => boolean,
  maxAttempts: number = 10,
  baseDelayMs: number = 100
): Promise<boolean> => {
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    if (checkFn()) {
      return true;
    }
    const delay = Math.min(baseDelayMs * Math.pow(2, attempt), 2000);
    await new Promise(resolve => setTimeout(resolve, delay));
  }
  return checkFn();
};

// Query to get conversation details including group chat and roleplay info
const CONVERSATION_DETAIL_QUERY = gql`
  query ConversationDetail($id: String!) {
    conversation(id: $id) {
      id
      title
      isGroupChat
      turnMode
      groupState
      maxConsecutiveResponses
      consecutiveResponseCount
      processingStatus { conversationId runId sequence stage label active updatedAt }
      currentSpeaker {
        id
        name
        avatar
      }
      lastSpeaker {
        id
        name
        avatar
      }
      character {
        id
        personality
        gender
        pronouns
        ageYears
        name
        avatar
        greeting
      }
      speakerColorSlots {
        characterId
        slot
      }
      characters {
        id
        character {
          id
          personality
          gender
          pronouns
          ageYears
          name
          description
          avatar
          systemPrompt
        }
        turnOrder
        responseCount
        isActive
      }
      # Roleplay fields
      isRoleplay
      rpMode
      agentPipelineEnabled
      activeSceneId
    }
  }
`;


interface ChatInterfaceProps {
  className?: string;
}

export const ChatInterface = ({ className }: ChatInterfaceProps) => {
  const { conversationId: paramConversationId } = useParams<{ conversationId: string }>();
  const navigate = useNavigate();
  const location = useLocation();

  // Use pathname to determine if we're in "new conversation" mode
  // This is more reliable than just checking useParams because params might have stale values
  const isNewConversationMode = location.pathname === '/chat';
  const conversationId = isNewConversationMode ? undefined : paramConversationId;
  const [message, setMessage] = useState('');
  const [locallyGenerating, setIsGenerating] = useState(false);
  const [liveProgress, setLiveProgress] = useState<ConversationProgress | null>(null);
  const [showCharacterSelector, setShowCharacterSelector] = useState(false);
  const [showConversationSettings, setShowConversationSettings] = useState(false);
  const [showGroupCreator, setShowGroupCreator] = useState(false);
  const [showInterjectionModal, setShowInterjectionModal] = useState(false);
  const [showParticipantList, setShowParticipantList] = useState(false);
  const [showWorldState, setShowWorldState] = useState(false);
  const battleId = new URLSearchParams(location.search).get('battle') || undefined;
  useEffect(() => { if (battleId) setShowWorldState(true); }, [battleId, location.key]);
  const narratorMode = useNarratorMode(conversationId);
  const setNarratorMode = (enabled: boolean) => { if (conversationId) useNarratorModeStore.getState().setEnabled(conversationId, enabled); };
  const [initComplete, setInitComplete] = useState(false);
  const [selectedCharacter, setSelectedCharacter] = useState<SelectedCharacter | null>(null);
  const [attachments, setAttachments] = useState<File[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const sendInFlightRef = useRef(false);
  const messageListReadyRef = useRef(false);

  // Callback for MessageList to signal it's ready
  const handleMessageListReady = useCallback(() => {
    messageListReadyRef.current = true;
  }, []);

  // Reset ready state when conversation changes
  useEffect(() => {
    messageListReadyRef.current = !!conversationId;
  }, [conversationId]);

  const { createConversation } = useConversationStore();
  const { user: currentUser } = useAuthStore();

  // Fetch user participants for multi-user conversations
  const { participants: userParticipants } = useConversationParticipants(conversationId || '');

  // Determine if this is a multi-user conversation (more than one human participant)
  const isMultiUser = useMemo(() => {
    return userParticipants.length > 1;
  }, [userParticipants]);

  // Fetch conversation details to detect group chat
  const { data: conversationData, refetch: refetchConversation, client: conversationClient } = useQuery(CONVERSATION_DETAIL_QUERY, {
    variables: { id: conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  const conversation = conversationData?.conversation;
  const isGroupChat = conversation?.isGroupChat === true;
  const isRoleplay = conversation?.isRoleplay === true;
  const useGroupPath = isGroupChat || isRoleplay;
  const groupState = conversation?.groupState || 'WAITING_FOR_USER';
  const processing = latestProgress(conversation?.processingStatus,
    liveProgress?.conversationId === conversationId ? liveProgress : null);
  const processingLabel = processing?.active ? processing.label : undefined;
  const currentSpeaker = processingLabel ? null : conversation?.currentSpeaker;
  const groupBusy = useGroupPath && ['CHARACTER_RESPONDING','INITIALIZING'].includes(groupState);
  const isGenerating = locallyGenerating || groupBusy || !!processingLabel;
  const participants = conversation?.characters || [];

  // Achievement popup for roleplay
  const { achievement: achievementEvent } = useAchievementUnlocked(currentUser?.id || '');
  const [pendingAchievement, setPendingAchievement] = useState<any>(null);

  useEffect(() => {
    if (achievementEvent?.achievement && isRoleplay) {
      setPendingAchievement(achievementEvent.achievement);
    }
  }, [achievementEvent, isRoleplay]);

  // Reset state when entering new conversation mode
  useEffect(() => {
    if (isNewConversationMode) {
      setIsGenerating(false);
      setSelectedCharacter(null);
      setMessage('');
      setShowWorldState(false);
      setInitComplete(false);
    }
  }, [isNewConversationMode]);

  // Reset init state on conversation change
  useEffect(() => {
    setInitComplete(false);
    setLiveProgress(null);
  }, [conversationId]);

  // Get TTS stopAll for cleanup on conversation change
  const { stopAll: stopAllTTS } = useTTSStore();

  // Stop TTS playback when switching conversations or starting a new one
  // This prevents audio from previous conversation playing over new content
  useEffect(() => {
    // Stop all TTS playback when conversation changes
    stopAllTTS();
  }, [conversationId, stopAllTTS]);

  // Update selected character from conversation data
  useEffect(() => {
    if (conversation?.character && !isGroupChat) {
      setSelectedCharacter({
        id: conversation.character.id,
        name: conversation.character.name,
        avatar: conversation.character.avatar,
        greeting: conversation.character.greeting,
        personality: conversation.character.personality,
        gender: conversation.character.gender, pronouns: conversation.character.pronouns, ageYears: conversation.character.ageYears,
      });
    }
  }, [conversation, isGroupChat]);

  // GraphQL mutations
  const { sendMessage } = useSendMessage();
  const { sendTypingIndicator: sendTypingMutation } = useSendTypingIndicator();
  const { switchCharacter } = useSwitchCharacter();
  const { stopGeneration } = useStopGeneration();

  // Group chat mutations
  const [requestInterjection, { loading: interjectionLoading }] = useMutation(REQUEST_INTERJECTION_MUTATION);
  const [pauseGroupConversation] = useMutation(PAUSE_GROUP_CONVERSATION_MUTATION);
  const [resumeGroupConversation] = useMutation(RESUME_GROUP_CONVERSATION_MUTATION);
  const { sendGroupMessage } = useSendGroupMessage();
  const [triggerNextCharacter, { loading: triggerNextLoading }] = useMutation(TRIGGER_NEXT_CHARACTER_RESPONSE_MUTATION);

  // Sync isGenerating with groupState when conversation loads or changes
  const isInitializing = useGroupPath && groupState === 'INITIALIZING';
  useEffect(() => {
    if (useGroupPath && groupState) {
      if (groupState === 'CHARACTER_RESPONDING' || groupState === 'INITIALIZING') {
        setIsGenerating(true);
      } else if (
        groupState === 'WAITING_FOR_USER' ||
        groupState === 'PAUSED' ||
        groupState === 'INTERJECTION_PENDING'
      ) {
        // INTERJECTION_PENDING means a request is queued, not that a character
        // is speaking. Treating it as busy left the composer disabled after a
        // send that started no turn.
        if (!triggerNextLoading) {
          setIsGenerating(false);
        }
      }
    }
  }, [useGroupPath, groupState, triggerNextLoading]);

  // Group chat / roleplay subscriptions (using custom WebSocket client)
  const { data: groupStateEvent } = useWebSocketSubscription(
    GROUP_STATE_CHANGED_QUERY,
    { conversationId },
    !conversationId || !useGroupPath
  );

  useEffect(() => {
    const stateData = groupStateEvent?.groupStateChanged;
    if (stateData && stateData.id === conversationId) {
      if (stateData.processingOnly) {
        if (stateData.processingStatus) {
          setLiveProgress(previous => latestProgress(previous, stateData.processingStatus));
          conversationClient?.cache.updateQuery({ query: CONVERSATION_DETAIL_QUERY, variables: { id: conversationId } }, (data: any) =>
            data?.conversation ? { ...data, conversation: { ...data.conversation, currentSpeaker: null } } : data);
          if (!stateData.processingStatus.active) void refetchConversation();
        }
        return;
      }
      const newState = stateData.groupState;
      if (!['CHARACTER_RESPONDING','INITIALIZING'].includes(newState)) setLiveProgress(null);
      conversationClient?.cache.updateQuery({ query: CONVERSATION_DETAIL_QUERY, variables: { id: conversationId } }, (data: any) =>
        data?.conversation ? { ...data, conversation: { ...data.conversation, groupState: newState,
          currentSpeaker: stateData.currentSpeaker ?? null, processingStatus: stateData.processingStatus ?? null } } : data);
      if (newState === 'CHARACTER_RESPONDING' || newState === 'INITIALIZING') {
        setIsGenerating(true);
      } else if (
        newState === 'WAITING_FOR_USER' ||
        newState === 'PAUSED' ||
        newState === 'INTERJECTION_PENDING'
      ) {
        setIsGenerating(false);
      }
      refetchConversation();
    }
  }, [groupStateEvent]);

  const { data: turnEvent } = useWebSocketSubscription(
    TURN_CHANGED_QUERY,
    { conversationId },
    !conversationId || !useGroupPath
  );

  useEffect(() => {
    if (turnEvent?.turnChanged) {
      setLiveProgress(null);
      conversationClient?.cache.updateQuery({ query: CONVERSATION_DETAIL_QUERY, variables: { id: conversationId } }, (data: any) =>
        data?.conversation ? { ...data, conversation: { ...data.conversation, currentSpeaker: turnEvent.turnChanged, processingStatus: null } } : data);
      refetchConversation();
    }
  }, [turnEvent]);

  // Interjection subscriptions - refetch to update group state
  const { data: interjectionRequestedEvent } = useWebSocketSubscription(
    INTERJECTION_REQUESTED_QUERY,
    { conversationId },
    !conversationId || !useGroupPath
  );

  useEffect(() => {
    if (interjectionRequestedEvent?.interjectionRequested) {
      refetchConversation();
    }
  }, [interjectionRequestedEvent]);

  const { data: interjectionResolvedEvent } = useWebSocketSubscription(
    INTERJECTION_RESOLVED_QUERY,
    { conversationId },
    !conversationId || !useGroupPath
  );

  useEffect(() => {
    const resolution = interjectionResolvedEvent?.interjectionResolved;
    if (resolution) {
      if (resolution.status?.toLowerCase() === 'approved') {
        setIsGenerating(true);
      }
      refetchConversation();
    }
  }, [interjectionResolvedEvent]);

  // Character join subscription — refresh participant list when new characters are auto-created
  const { data: characterJoinedEvent } = useWebSocketSubscription(
    CHARACTER_JOINED_QUERY,
    { conversationId },
    !conversationId || !useGroupPath
  );

  useEffect(() => {
    if (characterJoinedEvent?.characterJoined) {
      refetchConversation();
    }
  }, [characterJoinedEvent]);

  // Fallback timeout: if isGenerating is true for too long, reset the state
  useEffect(() => {
    if (!isGenerating || useGroupPath) return;

    const fallbackTimeout = setTimeout(() => {
      setIsGenerating(false);
    }, 60000);

    return () => clearTimeout(fallbackTimeout);
  }, [isGenerating]);

  // Handle completion signal from MessageList (when AI response is detected)
  const handleGenerationComplete = useCallback(() => {
    if (!groupBusy && !processingLabel) setIsGenerating(false);
  }, [groupBusy, processingLabel]);

  // Auto-resize textarea
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 200)}px`;
    }
  }, [message]);

  // Focus textarea on mount
  useEffect(() => {
    if (textareaRef.current && !isGenerating) {
      textareaRef.current.focus();
    }
  }, [conversationId, isGenerating]);

  // Debounced typing indicator. When conversationId changes or the component
  // unmounts, the pending timeout is cleared so we don't fire a typing mutation
  // against a stale conversationId.
  const typingTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sendTypingIndicator = useMemo(() => {
    return () => {
      if (typingTimeoutRef.current !== null) {
        clearTimeout(typingTimeoutRef.current);
      }
      typingTimeoutRef.current = setTimeout(() => {
        typingTimeoutRef.current = null;
        if (conversationId) {
          sendTypingMutation({
            variables: {
              conversationId,
              isTyping: true,
            },
          }).catch(console.error);
        }
      }, 1000);
    };
  }, [conversationId, sendTypingMutation]);

  useEffect(() => {
    return () => {
      if (typingTimeoutRef.current !== null) {
        clearTimeout(typingTimeoutRef.current);
        typingTimeoutRef.current = null;
      }
    };
  }, [conversationId]);

  // Handle message change with typing indicator
  const handleMessageChange = useCallback((newValue: string) => {
    setMessage(newValue);
    if (newValue.length > 0) {
      sendTypingIndicator();
    }
  }, [sendTypingIndicator]);

  const handleSendMessage = async () => {
    if (sendInFlightRef.current || (!message.trim() && attachments.length === 0)) return;
    sendInFlightRef.current = true;

    const messageContent = message.trim();
    const files = [...attachments];

    // Clear input immediately for better UX
    setMessage('');
    setAttachments([]);
    setIsGenerating(true);

    try {
      // Create new conversation if needed
      let targetConversationId = conversationId;
      if (!targetConversationId) {
        // Reset ready state before creating conversation
        messageListReadyRef.current = false;

        const newConv = await createConversation(
          messageContent.slice(0, 50) + (messageContent.length > 50 ? '...' : ''),
          selectedCharacter?.id || undefined
        );

        if (!newConv) {
          throw new Error('Failed to create conversation');
        }
        targetConversationId = newConv.id;
        navigate(`/chat/${targetConversationId}`, { replace: true });

        // Wait for MessageList to mount and be ready with exponential backoff
        // Instead of a fixed 300ms, we'll wait up to ~4 seconds with increasing delays
        const isReady = await waitForCondition(
          () => messageListReadyRef.current,
          10, // 10 attempts
          100 // Start at 100ms
        );

        if (!isReady) {
          console.warn('MessageList did not signal ready in time, proceeding anyway');
          // Still proceed - the message will be sent and MessageList will catch up
        }
      }

      // Upload attachments if any. Read the token fresh from the store:
      // handleSendMessage closes over the accessToken from the render where
      // it was created, and a silent token refresh in the meantime would
      // otherwise leave the upload using a revoked/expired token.
      const liveAccessToken = useAuthStore.getState().accessToken;
      let uploadedAttachments: { filename: string; mimeType: string; size: number; url: string }[] = [];
      if (files.length > 0 && liveAccessToken) {
        try {
          const formData = new FormData();
          files.forEach((file) => {
            formData.append('file', file);
          });

          const uploadResponse = await fetch('/api/upload', {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${liveAccessToken}`,
            },
            body: formData,
          });

          if (uploadResponse.ok) {
            const uploadResult = await uploadResponse.json();
            uploadedAttachments = uploadResult.files.map((f: { filename: string; mimeType: string; size: number; url: string }) => ({
              filename: f.filename,
              mimeType: f.mimeType,
              size: f.size,
              url: f.url,
            }));
          } else {
            console.error('Failed to upload attachments:', await uploadResponse.text());
            toast.error('Failed to upload attachments');
          }
        } catch (uploadError) {
          console.error('Error uploading attachments:', uploadError);
          toast.error('Failed to upload attachments');
        }
      }

      // A send may have committed even if its HTTP response is lost. Do not replay it automatically.
      // Roleplay conversations use the group message path for character response + pipeline
      if (useGroupPath) {
        await sendGroupMessage({
          conversationId: targetConversationId!,
          content: messageContent,
        });
      } else {
        await sendMessage({
          conversationId: targetConversationId!,
          content: messageContent,
          attachments: uploadedAttachments.length > 0 ? uploadedAttachments : undefined,
        });
      }

    } catch (error) {
      console.error('Failed to send message:', error);
      // Restore message on error and stop generating on failure
      setMessage(messageContent);
      setIsGenerating(false);
      toast.error('Failed to send message. Please try again.');
    } finally {
      sendInFlightRef.current = false;
    }
  };

  const handleStopGeneration = async () => {
    if (!conversationId) return;

    try {
      await stopGeneration({
        variables: { conversationId },
      });
      setIsGenerating(false);
    } catch (error) {
      console.error('Failed to stop generation:', error);
    }
  };

  const handleCharacterSelect = async (character: SelectedCharacter | null) => {
    // Always update local state to show character is selected
    setSelectedCharacter(character);
    setShowCharacterSelector(false);

    // Only call switchCharacter mutation if we have an existing conversation
    if (conversationId && character) {
      try {
        await switchCharacter({
          variables: {
            conversationId,
            characterId: character.id,
          },
        });
      } catch (error) {
        console.error('Failed to switch character:', error);
      }
    }
  };

  // Group chat handlers
  const handleGroupCreated = (newConversationId: string) => {
    setShowGroupCreator(false);
    navigate(`/chat/${newConversationId}`);
  };

  const handlePauseGroup = async () => {
    if (!conversationId) return;
    try {
      await pauseGroupConversation({ variables: { conversationId } });
      toast.success('Group conversation paused');
    } catch (error) {
      console.error('Failed to pause group:', error);
      toast.error('Failed to pause group conversation');
    }
  };

  const handleResumeGroup = async () => {
    if (!conversationId) return;
    try {
      await resumeGroupConversation({ variables: { conversationId } });
      toast.success('Group conversation resumed');
    } catch (error) {
      console.error('Failed to resume group:', error);
      toast.error('Failed to resume group conversation');
    }
  };

  const handleTriggerNextCharacter = async () => {
    if (!conversationId) return;
    try {
      setIsGenerating(true);
      await triggerNextCharacter({ variables: { conversationId } });
    } catch (error) {
      console.error('Failed to trigger next character:', error);
      toast.error('Failed to trigger character response');
      setIsGenerating(false);
    }
  };

  const handleRequestInterjection = async (reason: string, priority: number) => {
    if (!conversationId) return;
    try {
      await requestInterjection({
        variables: {
          input: {
            conversationId,
            reason,
            priority,
          }
        }
      });
      setShowInterjectionModal(false);
      toast.success('Interjection requested');
    } catch (error) {
      console.error('Failed to request interjection:', error);
      toast.error('Failed to request interjection');
    }
  };

  const isWelcome = !conversationId && !selectedCharacter?.greeting && !useGroupPath && !isGenerating;
  const chatToolbarHost = useContext(ChatToolbarContext);

  const toolsTakeOver = isRoleplay && showWorldState && !!conversationId;
  const roleplayControls: RoleplayHeaderControls | undefined = isRoleplay
    ? {
        narratorMode,
        onNarratorModeChange: setNarratorMode,
        toolsOpen: showWorldState,
        onToggleTools: () => setShowWorldState((open) => !open),
      }
    : undefined;
  const regularToolbar = <>
    <InviteNotificationBadge />
    <button type="button" onClick={() => { setShowCharacterSelector(open => !open); setShowConversationSettings(false); }}
      className={cn('flex h-10 min-w-10 items-center justify-center gap-1.5 rounded-lg px-2 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground', showCharacterSelector && 'bg-accent text-foreground')}
      aria-label="Select character" title={selectedCharacter ? `Character: ${selectedCharacter.name}` : 'Select character'} aria-expanded={showCharacterSelector}>
      {selectedCharacter?.avatar ? <img src={selectedCharacter.avatar} alt="" className="h-5 w-5 rounded object-cover" /> : <Bot size={18} />}
      <span className="hidden max-w-28 truncate xl:inline">{selectedCharacter?.name || 'Character'}</span>
    </button>
    <ChatOptionsMenu conversationId={conversationId} participantCount={isMultiUser ? userParticipants.length : undefined}
      onSettings={() => { setShowConversationSettings(open => !open); setShowCharacterSelector(false); }}
      onCreateGroup={() => setShowGroupCreator(true)} onParticipants={() => setShowParticipantList(true)} />
  </>;


  return (
    <div className={cn('flex h-full min-h-0 min-w-0 flex-col bg-background', className)}>
      {/* Header wrapper - relative positioning for dropdown panels. On phones
          the open roleplay tools take over this space; their back button
          returns to the conversation and its header. */}
      <div className={cn('relative', toolsTakeOver && 'max-md:hidden')}>
        {/* Render different header based on conversation type (useGroupPath includes roleplay) */}
        {useGroupPath ? (
            <GroupChatHeader
              title={conversation?.title || (isRoleplay ? 'Roleplay' : 'Group Chat')}
              participants={participants}
              currentSpeaker={currentSpeaker}
              processingLabel={processingLabel}
              groupState={groupState}
              turnMode={conversation?.turnMode || 'random'}
              conversationId={conversationId}
              onPause={handlePauseGroup}
              onResume={handleResumeGroup}
              onRequestInterjection={() => setShowInterjectionModal(true)}
              onTriggerNextCharacter={handleTriggerNextCharacter}
              onStop={() => void handleStopGeneration()}
              isGenerating={isGenerating || triggerNextLoading}
              onOpenSettings={() => {
                setShowConversationSettings(!showConversationSettings);
                setShowCharacterSelector(false);
              }}
              roleplay={roleplayControls}
            />
        ) : (
          chatToolbarHost ? createPortal(regularToolbar, chatToolbarHost) : (
            <div aria-label="Chat controls" className="flex shrink-0 items-center justify-end gap-1 border-b border-border px-3 py-1">{regularToolbar}</div>
          )
        )}

        {/* Character selector - positioned below header */}
        {showCharacterSelector && !isGroupChat && (
          <CharacterSelector
            selectedCharacterId={selectedCharacter?.id || null}
            onSelect={handleCharacterSelect}
            onClose={() => setShowCharacterSelector(false)}
          />
        )}

        {/* Conversation settings - positioned below header */}
        {showConversationSettings && (
          <ConversationSettings
            conversationId={conversationId}
            onClose={() => setShowConversationSettings(false)}
          />
        )}
      </div>

      {/* Scene indicator for roleplay — renders nothing (no band, no border)
          while the conversation has no active scene. */}
      {isRoleplay && conversationId && <><EncounterNotice conversationId={conversationId} /><JourneyNotice conversationId={conversationId} /></>}
      {isRoleplay && conversationId && (
        <SceneIndicator
          conversationId={conversationId}
          className={cn('border-b border-border/50 px-4 py-2', toolsTakeOver && 'max-md:hidden')}
        />
      )}

      <div className={cn('flex min-h-0 flex-1 flex-col', isWelcome && 'overflow-y-auto')}>
      <div className={cn('flex min-h-0 flex-1 flex-col', isWelcome && 'my-auto flex-none py-5')}>
      {/* Messages area with optional world state sidebar */}
      <div className={cn('relative flex min-h-0', isWelcome ? 'flex-none' : 'flex-1 overflow-hidden')}>
        {/* Main message area */}
        <div className="relative min-w-0 flex-1 overflow-hidden">
          <MessageList
            key={conversationId || 'new-conversation'}
            conversationId={conversationId}
            isGenerating={isGenerating}
            onGenerationComplete={handleGenerationComplete}
            allowStoryEditing={!isRoleplay || narratorMode}
            onReady={handleMessageListReady}
            characterGreeting={selectedCharacter?.greeting}
            characterName={selectedCharacter?.name}
            characterAvatar={selectedCharacter?.avatar}
            voiceCharacter={selectedCharacter || undefined}
            isGroupChat={isGroupChat}
            participants={participants}
            speakerColorSlots={conversation?.speakerColorSlots}
            currentSpeaker={currentSpeaker}
            processingLabel={processingLabel}
            isMultiUser={isMultiUser}
            userParticipants={userParticipants}
            currentUserId={currentUser?.id}
          />
          {conversationId && <TypingIndicator conversationId={conversationId} />}

          {/* Roleplay initialization overlay */}
          {isInitializing && !initComplete && conversationId && (
            <Suspense fallback={null}>
              <RoleplayInitScreen
                conversationId={conversationId}
                onComplete={() => setInitComplete(true)}
              />
            </Suspense>
          )}
        </div>

        {/* Roleplay tool panel */}
        {isRoleplay && showWorldState && conversationId && (
          <Suspense fallback={<div className="w-full flex-shrink-0 border-l border-border/50 bg-background-secondary/30 md:w-96" />}>
            <RoleplayToolPanel
              conversationId={conversationId}
              participants={participants}
              currentUserId={currentUser?.id}
              currentUsername={currentUser?.username}
              narratorMode={narratorMode}
              battleId={battleId}
              onClose={() => setShowWorldState(false)}
            />
          </Suspense>
        )}
      </div>

      {/* Input area */}
      <div className={cn(
        'shrink-0 bg-background px-3 pb-2 pt-1 sm:px-6 sm:pb-3'
      )}>
        <ChatInput
          className="mx-auto max-w-3xl"
          ref={textareaRef}
          value={message}
          onChange={handleMessageChange}
          onSend={handleSendMessage}
          onStop={handleStopGeneration}
          isGenerating={isGenerating}
          isDisabled={false}
          isPaused={useGroupPath && groupState === 'PAUSED'}
          processingLabel={processingLabel}
          currentSpeakerName={useGroupPath ? currentSpeaker?.name : undefined}
          attachments={attachments}
          onAttachmentsChange={setAttachments}
          onFileSelect={(files) => setAttachments((prev) => [...prev, ...files])}
        />
      </div>

      {isWelcome && (
        <div className="mx-auto flex w-full max-w-3xl flex-wrap justify-center gap-2 px-4 pb-4">
          {[
            { label: 'Explain a concept', prompt: 'Help me understand a complex topic. Ask me what I want to explore.' },
            { label: 'Write a story', prompt: 'Help me write a creative story. Ask me about the world I want to create.' },
            { label: 'Help with code', prompt: 'Help me write code. Ask me what I want to build.' },
          ].map(({ label, prompt }) => <button key={label} type="button" onClick={() => { setMessage(prompt); textareaRef.current?.focus(); }} className="min-h-10 rounded-lg border border-border bg-card px-3 py-2 text-xs text-muted-foreground transition-colors hover:border-primary hover:text-primary">{label}</button>)}
        </div>
      )}
      </div>
      </div>

      {/* Group conversation creator modal */}
      {showGroupCreator && (
        <GroupConversationCreator
          onCreated={handleGroupCreated}
          onClose={() => setShowGroupCreator(false)}
        />
      )}

      {/* Interjection modal */}
      {showInterjectionModal && (
        <InterjectionModal
          isOpen={showInterjectionModal}
          onClose={() => setShowInterjectionModal(false)}
          onSubmit={handleRequestInterjection}
          isLoading={interjectionLoading}
        />
      )}

      {/* Participant list modal */}
      <ParticipantList
        isOpen={showParticipantList}
        onClose={() => setShowParticipantList(false)}
        conversationId={conversationId || ''}
        conversationTitle={conversation?.title}
      />

      {/* Achievement popup (roleplay) */}
      {pendingAchievement && (
        <div className="pointer-events-none fixed bottom-6 right-6 z-50">
          <Suspense fallback={null}>
            <AchievementPopup
              achievement={pendingAchievement}
              onDismiss={() => setPendingAchievement(null)}
            />
          </Suspense>
        </div>
      )}
    </div>
  );
};

// Keep conversation actions together; application settings live in the account menu.
const ChatOptionsMenu = ({ conversationId, participantCount, onSettings, onCreateGroup, onParticipants }: {
  conversationId?: string;
  participantCount?: number;
  onSettings: () => void;
  onCreateGroup: () => void;
  onParticipants: () => void;
}) => {
  const { settings, setEnabled, stopAll } = useTTSStore();
  const itemClass = 'flex cursor-pointer items-center gap-2 rounded-md px-3 py-2.5 text-xs outline-none data-[highlighted]:bg-accent';
  return <DropdownMenu.Root>
    <DropdownMenu.Trigger asChild><button type="button" aria-label="Chat options" title="Chat options" className="workspace-icon-button"><MoreHorizontal size={19} /></button></DropdownMenu.Trigger>
    <DropdownMenu.Portal>
      <DropdownMenu.Content align="end" sideOffset={8} className="z-50 w-56 rounded-xl border border-border bg-popover p-1.5 text-popover-foreground shadow-lg">
        <DropdownMenu.Label className="px-3 py-2 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">This conversation</DropdownMenu.Label>
        <DropdownMenu.Item className={itemClass} onSelect={onSettings}><Settings size={16} />Conversation settings</DropdownMenu.Item>
        <DropdownMenu.CheckboxItem className={itemClass} checked={settings.enabled} onCheckedChange={enabled => { if (!enabled) stopAll(); setEnabled(enabled); }}><Volume2 size={16} />Read responses aloud<DropdownMenu.ItemIndicator className="ml-auto"><Check size={14} /></DropdownMenu.ItemIndicator></DropdownMenu.CheckboxItem>
        <DropdownMenu.Separator className="my-1 h-px bg-border" />
        {conversationId ? <DropdownMenu.Item className={itemClass} onSelect={onParticipants}><Users size={16} />Participants{participantCount !== undefined && <span className="ml-auto text-muted-foreground">{participantCount}</span>}</DropdownMenu.Item> : <DropdownMenu.Item className={itemClass} onSelect={onCreateGroup}><Users size={16} />Create group chat</DropdownMenu.Item>}
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  </DropdownMenu.Root>;
};

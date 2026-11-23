import React, { useEffect, useRef } from 'react';
import { useParams } from 'react-router-dom';
import { ChatInterface } from '../components/chat/ChatInterface';
import { useConversationStore } from '../stores/conversationStore';
import { useMessageStore } from '../stores/messageStore';

const ChatPage: React.FC = () => {
  const { conversationId } = useParams<{ conversationId?: string }>();
  const {
    setSelectedConversation,
    fetchConversation
  } = useConversationStore();
  const { clearMessages } = useMessageStore();

  // Track previous conversation ID to clear messages when switching
  const prevConversationIdRef = useRef<string | undefined>(undefined);

  useEffect(() => {
    // Clear messages from the previous conversation when switching
    const prevId = prevConversationIdRef.current;
    if (prevId && prevId !== conversationId) {
      clearMessages(prevId);
    }
    prevConversationIdRef.current = conversationId;

    if (conversationId) {
      // Load specific conversation
      fetchConversation(conversationId);
      setSelectedConversation(conversationId);
    } else {
      // Clear current conversation if no ID (new conversation mode)
      setSelectedConversation(null);
    }
  }, [conversationId, fetchConversation, setSelectedConversation, clearMessages]);

  return (
    <div className="h-full">
      <ChatInterface />
    </div>
  );
};

export default ChatPage;

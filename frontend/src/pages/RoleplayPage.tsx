import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { Swords, BookOpen, Dices, Users } from 'lucide-react';
import { PageHeader } from '@/components/layout/PageHeader';
import { PageTabs } from '@/components/layout/PageTabs';
import RoleplayConversationCreator from '@/components/roleplay/RoleplayConversationCreator';
import StoryTemplateSelector from '@/components/roleplay/StoryTemplateSelector';
import type { StoryTemplate } from '@/graphql/types';

const RoleplayPage: React.FC = () => {
  const navigate = useNavigate();
  const [showCreator, setShowCreator] = useState(false);
  const [selectedTemplate, setSelectedTemplate] = useState<StoryTemplate | null>(null);
  const [activeTab, setActiveTab] = useState<'create' | 'templates'>('create');
  const [creatorMode, setCreatorMode] = useState<'solo' | 'group' | null>(null);

  const handleConversationCreated = (conversationId: string) => {
    setShowCreator(false);
    navigate(`/chat/${conversationId}`);
  };

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <PageHeader
        icon={Swords}
        title="Roleplay"
        description="Create and manage roleplay adventures"
        measure="max-w-4xl"
      >
        <PageTabs
          label="Roleplay sections"
          value={activeTab}
          onChange={(id) => setActiveTab(id as 'create' | 'templates')}
          tabs={[
            { id: 'create', label: 'Quick Start', icon: Dices },
            { id: 'templates', label: 'Templates', icon: BookOpen },
          ]}
        />
      </PageHeader>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-4 py-6 sm:px-6 sm:py-8">
        {activeTab === 'create' && (
          <div className="mx-auto max-w-4xl space-y-6">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {[
                {
                  title: 'Solo Adventure',
                  description: 'One AI character guides you through the story.',
                  icon: Swords,
                  iconClass: 'text-primary',
                  hoverClass: 'hover:border-primary/40',
                  mode: 'solo' as const,
                },
                {
                  title: 'Group Story',
                  description: 'A collaborative narrative with multiple characters taking turns.',
                  icon: Users,
                  iconClass: 'text-secondary',
                  hoverClass: 'hover:border-secondary/40',
                  mode: 'group' as const,
                },
                {
                  title: 'From Template',
                  description: 'Start from a pre-built world with characters and lore.',
                  icon: BookOpen,
                  iconClass: 'text-warning',
                  hoverClass: 'hover:border-warning/40',
                  mode: null,
                },
              ].map((card) => (
                <button
                  type="button"
                  key={card.title}
                  onClick={() => {
                    if (card.title === 'From Template') {
                      setActiveTab('templates');
                    } else {
                      setCreatorMode(card.mode);
                      setShowCreator(true);
                    }
                  }}
                  className={cn(
                    'flex items-start gap-4 rounded-2xl border border-border bg-card p-4 text-left sm:flex-col sm:p-5',
                    'transition-colors',
                    card.hoverClass
                  )}
                >
                  <card.icon size={26} strokeWidth={1.5} className={cn('shrink-0', card.iconClass)} aria-hidden="true" />
                  <div className="min-w-0">
                    <h3 className="text-sm font-semibold text-foreground">{card.title}</h3>
                    <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{card.description}</p>
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}

        {activeTab === 'templates' && (
          <div className="mx-auto max-w-4xl">
            <StoryTemplateSelector
              onSelectTemplate={(template) => {
                setSelectedTemplate(template);
                setShowCreator(true);
              }}
              selectedTemplateId={selectedTemplate?.templateId}
            />
          </div>
        )}
      </div>

      {/* Creator modal */}
      <RoleplayConversationCreator
        isOpen={showCreator}
        onClose={() => {
          setShowCreator(false);
          setSelectedTemplate(null);
          setCreatorMode(null);
        }}
        onCreated={handleConversationCreated}
        initialTemplate={selectedTemplate}
        initialMode={creatorMode ?? undefined}
      />
    </div>
  );
};

export default RoleplayPage;

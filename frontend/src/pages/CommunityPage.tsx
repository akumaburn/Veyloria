import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Globe, BookOpen, Sparkles } from 'lucide-react';
import { PageHeader } from '@/components/layout/PageHeader';
import { PageTabs } from '@/components/layout/PageTabs';

const StoryBrowser = React.lazy(() => import('@/components/roleplay/StoryBrowser'));
const TemplateBrowser = React.lazy(() => import('@/components/roleplay/TemplateBrowser'));

const CommunityPage: React.FC = () => {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<'stories' | 'templates'>('stories');

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <PageHeader
        icon={Globe}
        title="Community"
        description="Discover and share stories and templates"
        measure="max-w-6xl"
      >
        <PageTabs
          label="Community sections"
          value={activeTab}
          onChange={(id) => setActiveTab(id as 'stories' | 'templates')}
          tabs={[
            { id: 'stories', label: 'Published Stories', icon: BookOpen },
            { id: 'templates', label: 'Templates', icon: Sparkles },
          ]}
        />
      </PageHeader>

      <div className="flex-1 overflow-y-auto px-4 py-6 sm:px-6 sm:py-8">
        <div className="mx-auto max-w-6xl">
          <React.Suspense fallback={<div className="flex justify-center py-12"><div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" /></div>}>
            {activeTab === 'stories' && (
              <StoryBrowser
                onSelectStory={(storyId: string) => navigate(`/community/story/${storyId}`)}
              />
            )}
            {activeTab === 'templates' && (
              <TemplateBrowser onUseTemplate={(templateId) => navigate(`/roleplay?template=${templateId}`)} />
            )}
          </React.Suspense>
        </div>
      </div>
    </div>
  );
};

export default CommunityPage;

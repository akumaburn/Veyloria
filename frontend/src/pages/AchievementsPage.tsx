import React from 'react';
import { Trophy } from 'lucide-react';
import AchievementList from '@/components/roleplay/AchievementList';
import ProgressionBar from '@/components/roleplay/ProgressionBar';
import { PageHeader } from '@/components/layout/PageHeader';
import { useAuthStore } from '@/stores/authStore';

const AchievementsPage: React.FC = () => {
  const { user } = useAuthStore();

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <PageHeader
        icon={Trophy}
        title="Achievements"
        description="Track your progress and unlock rewards"
        measure="max-w-4xl"
      />

      <div className="flex-1 overflow-y-auto px-4 py-8 sm:px-6">
        <div className="mx-auto max-w-4xl space-y-6">
          <ProgressionBar userId={user?.id} />
          <AchievementList userId={user?.id} />
        </div>
      </div>
    </div>
  );
};

export default AchievementsPage;

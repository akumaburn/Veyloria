import React from 'react';
import { Settings } from 'lucide-react';
import { SettingsView } from '../components/settings/SettingsView';
import { PageHeader } from '@/components/layout/PageHeader';

const SettingsPage: React.FC = () => {
  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <PageHeader
        icon={Settings}
        title="Settings"
        description="Configure your Veyloria experience"
      />

      <div className="flex-1 overflow-hidden">
        <SettingsView />
      </div>
    </div>
  );
};

export default SettingsPage;

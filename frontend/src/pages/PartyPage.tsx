import React, { useState } from 'react';
import { Users } from 'lucide-react';
import { PageHeader } from '@/components/layout/PageHeader';
import PartyPanel from '@/components/roleplay/PartyPanel';
import PartyCreator from '@/components/roleplay/PartyCreator';
import PartyInvite from '@/components/roleplay/PartyInvite';

const PartyPage: React.FC = () => {
  const [showCreator, setShowCreator] = useState(false);
  const [showJoin, setShowJoin] = useState(false);

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <PageHeader
        icon={Users}
        title="Parties"
        description="Form groups for collaborative adventures"
        measure="max-w-4xl"
      />

      <div className="flex-1 overflow-y-auto px-4 py-6 sm:px-6 sm:py-8">
        <div className="mx-auto max-w-4xl">
          <PartyPanel onCreateParty={() => setShowCreator(true)} onJoinParty={() => setShowJoin(true)} />
        </div>
      </div>

      {showCreator && (
        <PartyCreator
          isOpen={showCreator}
          onClose={() => setShowCreator(false)}
        />
      )}

      {showJoin && (
        <PartyInvite
          isOpen={showJoin}
          onClose={() => setShowJoin(false)}
        />
      )}
    </div>
  );
};

export default PartyPage;

import React from 'react';
import { Database } from 'lucide-react';
import { DataExportImport } from '../components/DataExportImport';
import { PageHeader } from '@/components/layout/PageHeader';

const DataManagementPage: React.FC = () => {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader
        icon={Database}
        title="Data Management"
        description="Export, import, and manage your Veyloria data"
        measure="max-w-4xl"
      />

      <div className="custom-scrollbar flex-1 overflow-y-auto px-4 py-6 sm:px-6 sm:py-8">
        <div className="mx-auto max-w-4xl">
          <DataExportImport />
        </div>
      </div>
    </div>
  );
};

export default DataManagementPage;

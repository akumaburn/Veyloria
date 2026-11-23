import React from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { BookOpen, ArrowLeft } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { PageHeader } from '@/components/layout/PageHeader';

const StoryReader = React.lazy(() => import('@/components/roleplay/StoryReader'));

const StoryReaderPage: React.FC = () => {
  const { storyId } = useParams<{ storyId: string }>();
  const navigate = useNavigate();

  if (!storyId) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <BookOpen size={48} className="mx-auto mb-4 text-muted-foreground/50" />
          <h2 className="text-lg font-semibold text-foreground">Story Not Found</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            The story you are looking for does not exist.
          </p>
          <Button
            variant="outline"
            size="sm"
            className="mt-4"
            leftIcon={<ArrowLeft size={16} />}
            onClick={() => navigate('/community')}
          >
            Back to Community
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <PageHeader
        icon={BookOpen}
        title="Story Reader"
        measure="max-w-3xl"
        lead={
          <Button
            variant="ghost"
            size="icon"
            aria-label="Back to community"
            title="Back to community"
            onClick={() => navigate('/community')}
          >
            <ArrowLeft size={20} />
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto px-4 py-8 sm:px-6">
        <div className="mx-auto max-w-3xl">
          <React.Suspense fallback={<div className="flex justify-center py-12"><div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" /></div>}>
            <StoryReader
              storyId={storyId}
              onBack={() => navigate('/community')}
            />
          </React.Suspense>
        </div>
      </div>
    </div>
  );
};

export default StoryReaderPage;

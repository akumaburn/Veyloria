import { useState } from 'react';
import { cn } from '@/lib/utils';
import { useGenerateMap } from './hooks/useMapGeneration';
import { Wand2, Loader2 } from 'lucide-react';
import { toast } from 'react-hot-toast';

interface MapGeneratorProps {
  conversationId: string;
  onGenerated?: (mapId: string) => void;
}

const SCALE_OPTIONS = [
  { value: 'region', label: 'Region', desc: '512x512 grid, 100-160 locations' },
  { value: 'city', label: 'City', desc: '384x384 grid, 200-350 locations' },
  { value: 'continent', label: 'Continent', desc: '768x768 grid, 400-600 locations' },
];

const THEME_OPTIONS = [
  { value: 'temperate', label: 'Temperate' },
  { value: 'desert', label: 'Desert' },
  { value: 'arctic', label: 'Arctic' },
  { value: 'tropical', label: 'Tropical' },
];

export default function MapGenerator({ conversationId, onGenerated }: MapGeneratorProps) {
  const [name, setName] = useState('');
  const [mapScale, setMapScale] = useState('region');
  const [seed, setSeed] = useState('');
  const [waterLevel, setWaterLevel] = useState(0.3);
  const [theme, setTheme] = useState('temperate');
  const { generateMap, loading } = useGenerateMap();

  const handleGenerate = async () => {
    if (!name.trim()) {
      toast.error('Please enter a map name');
      return;
    }
    try {
      const result = await generateMap({
        conversationId,
        name: name.trim(),
        mapScale,
        seed: seed.trim() || undefined,
        waterLevel,
        theme,
      });
      const newMapId = result.data?.generateProceduralMap?.mapId;
      if (newMapId && onGenerated) {
        onGenerated(newMapId);
      }
    } catch {
      toast.error('Failed to generate map. Please try again.');
    }
  };

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-lg space-y-6 px-4 py-6 sm:p-6">
        <div className="text-center">
          <Wand2 className="mx-auto h-10 w-10 text-primary mb-3" />
          <h2 className="text-lg font-semibold text-foreground">Generate Procedural Map</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Create a terrain with biomes, locations, and connecting roads.
          </p>
        </div>

        {/* Map Name */}
        <div>
          <label className="block text-xs font-medium text-muted-foreground mb-1">Map Name</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="The Shattered Isles"
            className={cn(
              'h-11 w-full rounded-lg border border-input bg-muted px-3',
              'text-sm text-foreground placeholder-muted-foreground',
              'focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary'
            )}
          />
        </div>

        {/* Scale */}
        <div>
          <label className="block text-xs font-medium text-muted-foreground mb-2">Scale</label>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            {SCALE_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                type="button"
                aria-pressed={mapScale === opt.value}
                onClick={() => setMapScale(opt.value)}
                className={cn(
                  'rounded-lg border px-3 py-2.5 text-left transition-colors sm:p-3',
                  mapScale === opt.value
                    ? 'border-primary bg-primary/10'
                    : 'border-input bg-muted hover:border-border-hover'
                )}
              >
                <div className="text-sm font-medium text-foreground">{opt.label}</div>
                <div className="text-xs text-muted-foreground mt-0.5">{opt.desc}</div>
              </button>
            ))}
          </div>
        </div>

        {/* Theme */}
        <div>
          <label className="block text-xs font-medium text-muted-foreground mb-2">Theme</label>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {THEME_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                type="button"
                aria-pressed={theme === opt.value}
                onClick={() => setTheme(opt.value)}
                className={cn(
                  'min-h-11 rounded-lg border px-3 py-2 text-sm transition-colors',
                  theme === opt.value
                    ? 'border-primary bg-primary/10 text-foreground'
                    : 'border-input bg-muted text-muted-foreground hover:border-border-hover'
                )}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        {/* Water Level */}
        <div>
          <label className="block text-xs font-medium text-muted-foreground mb-1">
            Water Level: {(waterLevel * 100).toFixed(0)}%
          </label>
          <input
            type="range"
            min={0}
            max={0.6}
            step={0.05}
            value={waterLevel}
            onChange={(e) => setWaterLevel(parseFloat(e.target.value))}
            className="w-full accent-primary"
          />
        </div>

        {/* Seed */}
        <div>
          <label className="block text-xs font-medium text-muted-foreground mb-1">
            Seed <span className="text-muted-foreground">(optional)</span>
          </label>
          <input
            type="text"
            value={seed}
            onChange={(e) => setSeed(e.target.value)}
            placeholder="Random if left empty"
            className={cn(
              'h-11 w-full rounded-lg border border-input bg-muted px-3',
              'text-sm text-foreground placeholder-muted-foreground',
              'focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary'
            )}
          />
        </div>

        {/* Generate Button */}
        <button
          onClick={handleGenerate}
          disabled={!name.trim() || loading}
          className={cn(
            'flex w-full items-center justify-center gap-2 rounded-lg px-4 py-3',
            'text-sm font-medium transition-colors',
            name.trim() && !loading
              ? 'bg-primary text-primary-foreground hover:bg-primary-hover'
              : 'bg-accent text-muted-foreground cursor-not-allowed'
          )}
        >
          {loading ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" />
              Generating...
            </>
          ) : (
            <>
              <Wand2 className="h-4 w-4" />
              Generate Map
            </>
          )}
        </button>
      </div>
    </div>
  );
}

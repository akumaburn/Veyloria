import { useState } from 'react';
import { useAddMapLocation } from './hooks/useMapData';
import { LOCATION_TYPES } from './constants';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Textarea } from '@/components/ui/Textarea';
import { Select } from '@/components/ui/Select';
import { MapPin, Plus, Grid3X3 } from 'lucide-react';
import { toast } from 'react-hot-toast';

interface MapEditorProps {
  mapId: string;
  conversationId: string;
}

const locationTypeOptions = LOCATION_TYPES.map(t => ({ value: t, label: t.charAt(0).toUpperCase() + t.slice(1) }));

export default function MapEditor({ mapId, conversationId: _conversationId }: MapEditorProps) {
  const { addMapLocation, loading } = useAddMapLocation();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [locationType, setLocationType] = useState('town');
  const [x, setX] = useState('0');
  const [y, setY] = useState('0');
  const [z, setZ] = useState('0');
  const [isDiscovered, setIsDiscovered] = useState(true);

  const handleAdd = async () => {
    if (!name.trim()) {
      toast.error('Please enter a location name');
      return;
    }
    const coordinates = [x, y, z].map(value => value.trim() ? Number(value) : NaN);
    if (!coordinates.every(Number.isFinite)) {
      toast.error('Enter a valid number for each coordinate');
      return;
    }
    try {
      await addMapLocation({
        mapId,
        name: name.trim(),
        description: description.trim() || undefined,
        locationType,
        x: coordinates[0],
        y: coordinates[1],
        z: coordinates[2],
        isDiscovered,
      });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not add location');
      return;
    }
    setName('');
    setDescription('');
    setX('0');
    setY('0');
    setZ('0');
  };

  return (
    <div className="space-y-4 rounded-lg border border-border bg-muted/80 p-4">
      <div className="flex items-center gap-2">
        <Grid3X3 size={16} className="text-muted-foreground" />
        <h3 className="text-sm font-semibold text-foreground">Map Editor</h3>
      </div>

      <div className="space-y-3">
        <Input
          label="Location Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g., Dragon's Keep"
          leftIcon={<MapPin size={14} />}
        />

        <Textarea
          label="Description (optional)"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="A brief description of this location..."
          minRows={2}
        />

        <Select
          label="Location Type"
          options={locationTypeOptions}
          value={locationType}
          onChange={(e) => setLocationType(e.target.value)}
        />

        <div className="flex gap-3">
          <Input
            label="X"
            type="number"
            step="0.1"
            value={x}
            onChange={(e) => setX(e.target.value)}
          />
          <Input
            label="Y"
            type="number"
            step="0.1"
            value={y}
            onChange={(e) => setY(e.target.value)}
          />
          <Input
            label="Z (elevation)"
            type="number"
            step="0.1"
            value={z}
            onChange={(e) => setZ(e.target.value)}
          />
        </div>

        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={isDiscovered}
            onChange={(e) => setIsDiscovered(e.target.checked)}
            className="h-4 w-4 rounded border-input bg-accent text-primary focus:ring-primary/50"
          />
          <span className="text-sm text-foreground">Discovered (visible to players)</span>
        </label>

        <Button
          onClick={handleAdd}
          isLoading={loading}
          disabled={!name.trim()}
          leftIcon={<Plus size={14} />}
          className="w-full"
        >
          Add Location
        </Button>
      </div>
    </div>
  );
}

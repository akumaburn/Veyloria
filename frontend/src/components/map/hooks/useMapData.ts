import { useEffect } from 'react';
import { RESUME_JOURNEY_MUTATION } from '../graphql/map-operations';
import { useQuery, useMutation, useLazyQuery } from '@apollo/client';
import { print } from 'graphql';
import { useWebSocketSubscription } from '@/lib/websocket-client';
import type {
  WorldMap,
  MapLocation,
  MapCharacterPosition,
  MapItemPosition,
  LocalMap,
  TravelResult,
  JourneyState,
  JourneyStepResult,
  CulturalRegion,
  AddMapItemPositionInput,
  UpdateEntityPositionInput,
} from '@/graphql/types';
import {
  WORLD_MAPS_QUERY,
  LOCAL_MAP_QUERY,
  CREATE_WORLD_MAP,
  ADD_MAP_LOCATION,
  DISCOVER_LOCATION,
  MOVE_CHARACTER_ON_MAP,
  ENTITY_POSITIONS_QUERY,
  MAP_ITEM_POSITIONS_QUERY,
  NEARBY_LOCATIONS_QUERY,
  UPDATE_ENTITY_POSITION,
  ADD_MAP_ITEM_POSITION,
  REMOVE_MAP_ITEM_POSITION,
  DISCOVER_MAP_ITEM,
  REMOVE_MAP_LOCATION,
  MAP_ENTITY_MOVED_SUB,
  MAP_ITEM_DISCOVERED_SUB,
  TRAVEL_PREVIEW_QUERY,
  TRAVEL_TO_LOCATION,
  LOCATION_DISCOVERED_SUB,
  CULTURAL_REGIONS_QUERY,
  ACTIVE_JOURNEY_QUERY,
  BEGIN_JOURNEY_MUTATION,
  CANCEL_JOURNEY_MUTATION,
  TRAVEL_JOURNEY_STEP_SUB,
} from '../graphql/map-operations';

type MapLocationInput = Omit<MapLocation, 'locationId'>;

interface WorldMapsQueryData {
  worldMaps: WorldMap[];
}

interface WorldMapsQueryVars {
  conversationId: string;
}

interface LocalMapQueryData {
  localMap: LocalMap | null;
}

interface LocalMapQueryVars {
  conversationId: string;
}

export const useLocalMap = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery<LocalMapQueryData, LocalMapQueryVars>(LOCAL_MAP_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    localMap: data?.localMap ?? null,
    loading,
    error,
    refetch,
  };
};

interface EntityPositionsQueryData {
  entityPositions: MapCharacterPosition[];
}

interface EntityPositionsQueryVars {
  mapId: string;
}

interface MapItemPositionsQueryData {
  mapItemPositions: MapItemPosition[];
}

interface NearbyLocationsQueryData {
  nearbyLocations: MapLocation[];
}

interface NearbyLocationsQueryVars {
  mapId: string;
  x: number;
  y: number;
  z: number;
  radius: number;
}

interface AddMapLocationResult {
  addMapLocation: MapLocation;
}

interface AddMapLocationVariables {
  input: MapLocationInput;
}

interface CreateWorldMapResult {
  createWorldMap: WorldMap;
}

interface CreateWorldMapVariables {
  input: Omit<WorldMap, 'mapId'>;
}

interface DiscoverLocationResult {
  discoverLocation: MapLocation;
}

interface DiscoverLocationVariables {
  locationId: string;
}

interface MoveCharacterResult {
  moveCharacterOnMap: MapCharacterPosition;
}

interface MoveCharacterVariables {
  input: {
    mapId: string;
    characterId?: string;
    userId?: string;
    locationId?: string;
    x?: number;
    y?: number;
    z?: number;
  };
}

interface UpdateEntityPositionResult {
  updateEntityPosition: MapCharacterPosition;
}

interface UpdateEntityPositionVariables {
  input: UpdateEntityPositionInput;
}

interface AddMapItemPositionResult {
  addMapItemPosition: MapItemPosition;
}

interface AddMapItemPositionVariables {
  input: AddMapItemPositionInput;
}

interface RemoveMapItemPositionResult {
  removeMapItemPosition: boolean;
}

interface RemoveMapItemPositionVariables {
  id: string;
}

interface DiscoverMapItemResult {
  discoverMapItem: MapItemPosition;
}

interface DiscoverMapItemVariables {
  id: string;
}

interface RemoveMapLocationResult {
  removeMapLocation: boolean;
}

interface RemoveMapLocationVariables {
  locationId: string;
}

interface MapEntityMovedSubData {
  mapEntityMoved: MapCharacterPosition;
}

interface MapItemDiscoveredSubData {
  mapItemDiscovered: MapItemPosition;
}

interface ConversationIdVars {
  conversationId: string;
}

interface TravelPreviewQueryData {
  travelPreview: TravelResult;
}

interface TravelPreviewQueryVars {
  mapId: string;
  fromLocationId: string;
  toLocationId: string;
}

interface TravelToLocationResult {
  travelToLocation: TravelResult;
}

interface TravelToLocationVariables {
  mapId: string;
  toLocationId: string;
  characterIds?: string[];
}

interface LocationDiscoveredSubData {
  locationDiscovered: MapLocation;
}

interface ActiveJourneyQueryData {
  activeJourney: JourneyState | null;
}

interface BeginJourneyResult {
  beginJourney: JourneyState;
}

interface BeginJourneyVariables {
  mapId: string;
  toLocationId: string;
  characterIds?: string[];
}

interface CancelJourneyResult {
  cancelJourney: boolean;
}

interface CancelJourneyVariables {
  conversationId: string;
}

interface TravelJourneyStepSubData {
  travelJourneyStep: JourneyStepResult;
}

interface CulturalRegionsQueryData {
  culturalRegions: CulturalRegion[];
}

export const useWorldMaps = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery<WorldMapsQueryData, WorldMapsQueryVars>(WORLD_MAPS_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    maps: data?.worldMaps || [],
    loading,
    error,
    refetch,
  };
};

export const useCreateWorldMap = () => {
  const [createWorldMap, { loading, error }] = useMutation<CreateWorldMapResult, CreateWorldMapVariables>(CREATE_WORLD_MAP, {
    refetchQueries: ['WorldMaps'],
  });

  return {
    createWorldMap: (input: CreateWorldMapVariables['input']) =>
      createWorldMap({ variables: { input } }),
    loading,
    error,
  };
};

export const useAddMapLocation = () => {
  const [addMapLocation, { loading, error }] = useMutation<AddMapLocationResult, AddMapLocationVariables>(ADD_MAP_LOCATION, {
    refetchQueries: ['WorldMaps'],
  });

  return {
    addMapLocation: (input: AddMapLocationVariables['input']) =>
      addMapLocation({ variables: { input } }),
    loading,
    error,
  };
};

export const useDiscoverLocation = () => {
  const [discoverLocation, { loading, error }] = useMutation<DiscoverLocationResult, DiscoverLocationVariables>(DISCOVER_LOCATION, {
    refetchQueries: ['WorldMaps'],
  });

  return {
    discoverLocation: (locationId: string) =>
      discoverLocation({ variables: { locationId } }),
    loading,
    error,
  };
};

export const useMoveCharacterOnMap = () => {
  const [moveCharacterOnMap, { loading, error }] = useMutation<MoveCharacterResult, MoveCharacterVariables>(MOVE_CHARACTER_ON_MAP, {
    refetchQueries: ['WorldMaps', 'EntityPositions'],
  });

  return {
    moveCharacterOnMap: (input: MoveCharacterVariables['input']) =>
      moveCharacterOnMap({ variables: { input } }),
    loading,
    error,
  };
};

export const useEntityPositions = (mapId: string) => {
  const { data, loading, error, refetch } = useQuery<EntityPositionsQueryData, EntityPositionsQueryVars>(ENTITY_POSITIONS_QUERY, {
    variables: { mapId },
    skip: !mapId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    entityPositions: data?.entityPositions || [],
    loading,
    error,
    refetch,
  };
};

export const useMapItemPositions = (mapId: string) => {
  const { data, loading, error, refetch } = useQuery<MapItemPositionsQueryData, EntityPositionsQueryVars>(MAP_ITEM_POSITIONS_QUERY, {
    variables: { mapId },
    skip: !mapId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    itemPositions: data?.mapItemPositions || [],
    loading,
    error,
    refetch,
  };
};

export const useNearbyLocations = (mapId: string, x: number, y: number, z: number, radius: number) => {
  const { data, loading, error, refetch } = useQuery<NearbyLocationsQueryData, NearbyLocationsQueryVars>(NEARBY_LOCATIONS_QUERY, {
    variables: { mapId, x, y, z, radius },
    skip: !mapId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    nearbyLocations: data?.nearbyLocations || [],
    loading,
    error,
    refetch,
  };
};

export const useUpdateEntityPosition = () => {
  const [updateEntityPosition, { loading, error }] = useMutation<UpdateEntityPositionResult, UpdateEntityPositionVariables>(UPDATE_ENTITY_POSITION, {
    refetchQueries: ['EntityPositions'],
  });

  return {
    updateEntityPosition: (input: UpdateEntityPositionInput) =>
      updateEntityPosition({ variables: { input } }),
    loading,
    error,
  };
};

export const useAddMapItemPosition = () => {
  const [addMapItemPosition, { loading, error }] = useMutation<AddMapItemPositionResult, AddMapItemPositionVariables>(ADD_MAP_ITEM_POSITION, {
    refetchQueries: ['MapItemPositions'],
  });

  return {
    addMapItemPosition: (input: AddMapItemPositionInput) =>
      addMapItemPosition({ variables: { input } }),
    loading,
    error,
  };
};

export const useRemoveMapItemPosition = () => {
  const [removeMapItemPosition, { loading, error }] = useMutation<RemoveMapItemPositionResult, RemoveMapItemPositionVariables>(REMOVE_MAP_ITEM_POSITION, {
    refetchQueries: ['MapItemPositions'],
  });

  return {
    removeMapItemPosition: (id: string) =>
      removeMapItemPosition({ variables: { id } }),
    loading,
    error,
  };
};

export const useDiscoverMapItem = () => {
  const [discoverMapItem, { loading, error }] = useMutation<DiscoverMapItemResult, DiscoverMapItemVariables>(DISCOVER_MAP_ITEM, {
    refetchQueries: ['MapItemPositions'],
  });

  return {
    discoverMapItem: (id: string) =>
      discoverMapItem({ variables: { id } }),
    loading,
    error,
  };
};

export const useRemoveMapLocation = () => {
  const [removeMapLocation, { loading, error }] = useMutation<RemoveMapLocationResult, RemoveMapLocationVariables>(REMOVE_MAP_LOCATION, {
    refetchQueries: ['WorldMaps'],
  });

  return {
    removeMapLocation: (locationId: string) =>
      removeMapLocation({ variables: { locationId } }),
    loading,
    error,
  };
};

const MAP_ENTITY_MOVED_QUERY = print(MAP_ENTITY_MOVED_SUB);
const MAP_ITEM_DISCOVERED_QUERY = print(MAP_ITEM_DISCOVERED_SUB);

export const useMapEntityMoved = (mapId: string) => {
  const { data, loading, error } = useWebSocketSubscription<MapEntityMovedSubData>(
    MAP_ENTITY_MOVED_QUERY,
    { mapId },
    !mapId
  );

  return {
    latestPosition: data?.mapEntityMoved,
    loading,
    error,
  };
};

export const useMapItemDiscovered = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription<MapItemDiscoveredSubData>(
    MAP_ITEM_DISCOVERED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    discoveredItem: data?.mapItemDiscovered,
    loading,
    error,
  };
};

// ==================== Travel Hooks ====================

export const useTravelPreview = () => {
  const [fetchPreview, { data, loading, error }] = useLazyQuery<TravelPreviewQueryData, TravelPreviewQueryVars>(TRAVEL_PREVIEW_QUERY, {
    fetchPolicy: 'network-only',
  });

  return {
    fetchTravelPreview: (mapId: string, fromLocationId: string, toLocationId: string) =>
      fetchPreview({ variables: { mapId, fromLocationId, toLocationId } }),
    travelPreview: data?.travelPreview,
    loading,
    error,
  };
};

export const useTravelToLocation = () => {
  const [travelToLocation, { loading, error }] = useMutation<TravelToLocationResult, TravelToLocationVariables>(TRAVEL_TO_LOCATION, {
    refetchQueries: ['WorldMaps', 'EntityPositions'],
  });

  return {
    travelToLocation: (mapId: string, toLocationId: string, characterIds?: string[]) =>
      travelToLocation({ variables: { mapId, toLocationId, characterIds } }),
    loading,
    error,
  };
};

const LOCATION_DISCOVERED_QUERY = print(LOCATION_DISCOVERED_SUB);

export const useLocationDiscovered = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription<LocationDiscoveredSubData>(
    LOCATION_DISCOVERED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    discoveredLocation: data?.locationDiscovered,
    loading,
    error,
  };
};

// ==================== Journey (Step-by-Step Travel) ====================

export const useActiveJourney = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery<ActiveJourneyQueryData, ConversationIdVars>(ACTIVE_JOURNEY_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'network-only',
  });

  const { journeyStep } = useTravelJourneyStep(conversationId);
  useEffect(() => { if (journeyStep && conversationId) void refetch(); }, [journeyStep, conversationId, refetch]);
  return {
    activeJourney: data?.activeJourney || null,
    loading,
    error,
    refetch,
  };
};

export const useBeginJourney = () => {
  const [beginJourney, { loading, error }] = useMutation<BeginJourneyResult, BeginJourneyVariables>(BEGIN_JOURNEY_MUTATION, {
    refetchQueries: ['ActiveJourney'],
  });

  return {
    beginJourney: (mapId: string, toLocationId: string, characterIds?: string[]) =>
      beginJourney({ variables: { mapId, toLocationId, characterIds } }),
    loading,
    error,
  };
};

export const useCancelJourney = () => {
  const [cancelJourney, { loading, error }] = useMutation<CancelJourneyResult, CancelJourneyVariables>(CANCEL_JOURNEY_MUTATION, {
    refetchQueries: ['ActiveJourney', 'EntityPositions', 'LocalMap'],
  });

  return {
    cancelJourney: (conversationId: string) =>
      cancelJourney({ variables: { conversationId } }),
    loading,
    error,
  };
};

const TRAVEL_JOURNEY_STEP_QUERY = print(TRAVEL_JOURNEY_STEP_SUB);

export const useTravelJourneyStep = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription<TravelJourneyStepSubData>(
    TRAVEL_JOURNEY_STEP_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    journeyStep: data?.travelJourneyStep,
    loading,
    error,
  };
};

// ==================== Cultural Regions ====================

export const useCulturalRegions = (mapId: string) => {
  const { data, loading, error, refetch } = useQuery<CulturalRegionsQueryData, EntityPositionsQueryVars>(CULTURAL_REGIONS_QUERY, {
    variables: { mapId },
    skip: !mapId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    culturalRegions: data?.culturalRegions || [],
    loading,
    error,
    refetch,
  };
};

export const useResumeJourney = () => {
  const [resumeJourney, state] = useMutation(RESUME_JOURNEY_MUTATION, {
    refetchQueries: ['ActiveJourney', 'EntityPositions', 'LocalMap'], awaitRefetchQueries: true,
  });
  return { resumeJourney: (conversationId: string) => resumeJourney({ variables: { conversationId } }), ...state };
};

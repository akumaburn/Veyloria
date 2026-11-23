import { gql } from '@apollo/client';

// ==================== Fragments ====================

export const MAP_LOCATION_FRAGMENT = gql`
  fragment MapLocationFields on MapLocation {
    locationId
    mapId
    name
    description
    x
    y
    z
    icon
    locationType
    linkedSceneId
    isDiscovered
    movementCost
  }
`;

export const WORLD_MAP_FRAGMENT = gql`
  fragment WorldMapFields on WorldMap {
    mapId
    conversationId
    name
    mapType
    mapScale
    imageId
    parentMapId
    gridWidth
    gridHeight
    locations {
      ...MapLocationFields
    }
  }
  ${MAP_LOCATION_FRAGMENT}
`;

export const MAP_CHARACTER_POSITION_FRAGMENT = gql`
  fragment MapCharacterPositionFields on MapCharacterPosition {
    id
    mapId
    characterId
    userId
    locationId
    x
    y
    z
  }
`;

export const MAP_ITEM_POSITION_FRAGMENT = gql`
  fragment MapItemPositionFields on MapItemPosition {
    id
    mapId
    conversationId
    itemName
    itemDescription
    itemType
    x
    y
    z
    linkedLocationId
    isDiscovered
    createdAt
    updatedAt
  }
`;

// ==================== Queries ====================

export const WORLD_MAPS_QUERY = gql`
  query WorldMaps($conversationId: String!) {
    worldMaps(conversationId: $conversationId) {
      ...WorldMapFields
    }
  }
  ${WORLD_MAP_FRAGMENT}
`;

export const ENTITY_POSITIONS_QUERY = gql`
  query EntityPositions($mapId: String!) {
    entityPositions(mapId: $mapId) {
      ...MapCharacterPositionFields
    }
  }
  ${MAP_CHARACTER_POSITION_FRAGMENT}
`;

export const LOCAL_MAP_QUERY = gql`
  query LocalMap($conversationId: String!) {
    localMap(conversationId: $conversationId) {
      mapId
      locationId
      locationName
      locationType
      currentAreaId
      currentAreaName
      visitedAreaIds
      areas {
        localAreaId
        mapId
        locationId
        name
        description
        areaType
        x
        y
        isGateway
        isEntry
        sortOrder
      }
      connections {
        connectionId
        mapId
        locationId
        fromAreaId
        toAreaId
        label
      }
    }
  }
`;

export const MAP_ITEM_POSITIONS_QUERY = gql`
  query MapItemPositions($mapId: String!) {
    mapItemPositions(mapId: $mapId) {
      ...MapItemPositionFields
    }
  }
  ${MAP_ITEM_POSITION_FRAGMENT}
`;

export const NEARBY_LOCATIONS_QUERY = gql`
  query NearbyLocations($mapId: String!, $x: Float!, $y: Float!, $z: Float!, $radius: Float!) {
    nearbyLocations(mapId: $mapId, x: $x, y: $y, z: $z, radius: $radius) {
      ...MapLocationFields
    }
  }
  ${MAP_LOCATION_FRAGMENT}
`;

export const CALCULATE_DISTANCE_QUERY = gql`
  query CalculateDistance($x1: Float!, $y1: Float!, $z1: Float!, $x2: Float!, $y2: Float!, $z2: Float!) {
    calculateDistance(x1: $x1, y1: $y1, z1: $z1, x2: $x2, y2: $y2, z2: $z2) {
      distance
      fromX
      fromY
      fromZ
      toX
      toY
      toZ
    }
  }
`;

export const TERRAIN_DATA_QUERY = gql`
  query TerrainData($mapId: String!) {
    terrainData(mapId: $mapId) {
      width
      height
      heightmap
      waterLevel
      seed
      mapScale
      theme
    }
  }
`;

/**
 * The biome grid is another 1.4 MB of base64. Only the 2D view paints with it,
 * so it has its own query and stays out of the 3D map's payload.
 */
export const TERRAIN_BIOMES_QUERY = gql`
  query TerrainBiomes($mapId: String!) {
    terrainData(mapId: $mapId) {
      biomeGrid
    }
  }
`;

export const MAP_ROADS_QUERY = gql`
  query MapRoads($mapId: String!) {
    mapRoads(mapId: $mapId) {
      roadId
      fromLocationId
      toLocationId
      waypoints
      roadType
    }
  }
`;

export const CULTURAL_REGIONS_QUERY = gql`
  query CulturalRegions($mapId: String!) {
    culturalRegions(mapId: $mapId) {
      culture
      name
      centerX
      centerY
      radius
      worldX
      worldY
    }
  }
`;

// ==================== Mutations ====================

export const CREATE_WORLD_MAP = gql`
  mutation CreateWorldMap($input: WorldMapInput!) {
    createWorldMap(input: $input) {
      ...WorldMapFields
    }
  }
  ${WORLD_MAP_FRAGMENT}
`;

export const ADD_MAP_LOCATION = gql`
  mutation AddMapLocation($input: AddMapLocationInput!) {
    addMapLocation(input: $input) {
      ...MapLocationFields
    }
  }
  ${MAP_LOCATION_FRAGMENT}
`;

export const DISCOVER_LOCATION = gql`
  mutation DiscoverLocation($locationId: String!) {
    discoverLocation(locationId: $locationId) {
      ...MapLocationFields
    }
  }
  ${MAP_LOCATION_FRAGMENT}
`;

export const MOVE_CHARACTER_ON_MAP = gql`
  mutation MoveCharacterOnMap($input: MoveCharacterOnMapInput!) {
    moveCharacterOnMap(input: $input) {
      ...MapCharacterPositionFields
    }
  }
  ${MAP_CHARACTER_POSITION_FRAGMENT}
`;

export const UPDATE_ENTITY_POSITION = gql`
  mutation UpdateEntityPosition($input: UpdateEntityPositionInput!) {
    updateEntityPosition(input: $input) {
      ...MapCharacterPositionFields
    }
  }
  ${MAP_CHARACTER_POSITION_FRAGMENT}
`;

export const ADD_MAP_ITEM_POSITION = gql`
  mutation AddMapItemPosition($input: AddMapItemPositionInput!) {
    addMapItemPosition(input: $input) {
      ...MapItemPositionFields
    }
  }
  ${MAP_ITEM_POSITION_FRAGMENT}
`;

export const REMOVE_MAP_ITEM_POSITION = gql`
  mutation RemoveMapItemPosition($id: String!) {
    removeMapItemPosition(id: $id)
  }
`;

export const DISCOVER_MAP_ITEM = gql`
  mutation DiscoverMapItem($id: String!) {
    discoverMapItem(id: $id) {
      ...MapItemPositionFields
    }
  }
  ${MAP_ITEM_POSITION_FRAGMENT}
`;

export const REMOVE_MAP_LOCATION = gql`
  mutation RemoveMapLocation($locationId: String!) {
    removeMapLocation(locationId: $locationId)
  }
`;

export const GENERATE_PROCEDURAL_MAP = gql`
  mutation GenerateProceduralMap($input: GenerateMapInput!) {
    generateProceduralMap(input: $input) {
      mapId
      conversationId
      name
      mapType
      mapScale
      gridWidth
      gridHeight
    }
  }
`;

// ==================== Subscriptions ====================

export const MAP_ENTITY_MOVED_SUB = gql`
  subscription MapEntityMoved($mapId: String!) {
    mapEntityMoved(mapId: $mapId) {
      ...MapCharacterPositionFields
    }
  }
  ${MAP_CHARACTER_POSITION_FRAGMENT}
`;

export const MAP_ITEM_DISCOVERED_SUB = gql`
  subscription MapItemDiscovered($conversationId: String!) {
    mapItemDiscovered(conversationId: $conversationId) {
      ...MapItemPositionFields
    }
  }
  ${MAP_ITEM_POSITION_FRAGMENT}
`;

// ==================== Travel ====================

export const TRAVEL_PREVIEW_QUERY = gql`
  query TravelPreview($mapId: String!, $fromLocationId: String!, $toLocationId: String!) {
    travelPreview(mapId: $mapId, fromLocationId: $fromLocationId, toLocationId: $toLocationId) {
      distance
      effectiveCost
      timePeriodsRequired
      usesRoad
      roadType
      dominantBiome
      fromLocationId
      toLocationId
      fromLocationName
      toLocationName
      discoveredLocationIds
    }
  }
`;

export const TRAVEL_TO_LOCATION = gql`
  mutation TravelToLocation($mapId: String!, $toLocationId: String!, $characterIds: [String!]) {
    travelToLocation(mapId: $mapId, toLocationId: $toLocationId, characterIds: $characterIds) {
      distance
      effectiveCost
      timePeriodsRequired
      usesRoad
      roadType
      dominantBiome
      fromLocationId
      toLocationId
      fromLocationName
      toLocationName
      discoveredLocationIds
    }
  }
`;

export const LOCATION_DISCOVERED_SUB = gql`
  subscription LocationDiscovered($conversationId: String!) {
    locationDiscovered(conversationId: $conversationId) {
      ...MapLocationFields
    }
  }
  ${MAP_LOCATION_FRAGMENT}
`;

// ==================== Journey (Step-by-Step Travel) ====================

export const JOURNEY_STEP_RESULT_FRAGMENT = gql`
  fragment JourneyStepResultFields on JourneyStepResult {
    journeyId
    stepNumber
    totalSteps
    narration
    eventType
    x
    y
    z
    biome
    timeOfDay
    dayNumber
    journeyComplete
    journeyInterrupted
    journeyPaused
    battleId
    interruptReason
    discoveredLocationIds
  }
`;

export const ACTIVE_JOURNEY_QUERY = gql`
  query ActiveJourney($conversationId: String!) {
    activeJourney(conversationId: $conversationId) {
      journeyId
      conversationId
      mapId
      userId
      characterIds
      fromLocationId
      fromLocationName
      toLocationId
      toLocationName
      totalSteps
      currentStep
      status
      interruptReason
      distance
      effectiveCost
      timePeriodsRequired
      usesRoad
      stepResults {
        ...JourneyStepResultFields
      }
      createdAt
      updatedAt
    }
  }
  ${JOURNEY_STEP_RESULT_FRAGMENT}
`;

export const BEGIN_JOURNEY_MUTATION = gql`
  mutation BeginJourney($mapId: String!, $toLocationId: String!, $characterIds: [String!]) {
    beginJourney(mapId: $mapId, toLocationId: $toLocationId, characterIds: $characterIds) {
      journeyId
      conversationId
      mapId
      userId
      characterIds
      fromLocationId
      fromLocationName
      toLocationId
      toLocationName
      totalSteps
      currentStep
      status
      distance
      effectiveCost
      timePeriodsRequired
      usesRoad
    }
  }
`;

export const CANCEL_JOURNEY_MUTATION = gql`
  mutation CancelJourney($conversationId: String!) {
    cancelJourney(conversationId: $conversationId)
  }
`;

export const TRAVEL_JOURNEY_STEP_SUB = gql`
  subscription TravelJourneyStep($conversationId: String!) {
    travelJourneyStep(conversationId: $conversationId) {
      journeyId
      stepNumber
      totalSteps
      narration
      eventType
      x
      y
      z
      biome
      timeOfDay
      dayNumber
      journeyComplete
      journeyInterrupted
      journeyPaused
      battleId
      interruptReason
      discoveredLocationIds
    }
  }
`;

export const RESUME_JOURNEY_MUTATION = gql`
  mutation ResumeJourney($conversationId: String!) {
    resumeJourney(conversationId: $conversationId) { journeyId conversationId status currentStep }
  }
`;

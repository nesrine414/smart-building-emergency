export interface Building {
  id: string;
  name: string;
  address: string;
  latitude: number;
  longitude: number;
  floors: Floor[];
  totalOccupants?: number;
  status: 'SAFE' | 'WARNING' | 'DANGER' | 'EVACUATING';
}

export interface Floor {
  id: string;
  buildingId: string;
  name: string;
  level: number;
  rooms: Room[];
  floorPlanUrl?: string;
}

export interface Room {
  id: string;
  floorId: string;
  name: string;
  type: 'OFFICE' | 'HALLWAY' | 'STAIRWAY' | 'ELEVATOR' | 'LOBBY' | 'PARKING' | 'UTILITY' | 'OTHER';
  capacity?: number;
  currentOccupants?: number;
  sensors?: string[];
  x?: number;
  y?: number;
  width?: number;
  height?: number;
}

export interface MapPosition {
  buildingId: string;
  floorId: string;
  roomId?: string;
  x: number;
  y: number;
  label?: string;
  type: 'INCIDENT' | 'SENSOR' | 'EXIT' | 'USER' | 'HAZARD';
}

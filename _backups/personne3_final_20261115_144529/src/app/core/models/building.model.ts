export interface Building {
  id: string;
  name: string;
  address: string;
  floors: number;
}

export interface Room {
  id: string;
  buildingId: string;
  roomNumber: string;
  floor: number;
  x: number;
  y: number;
  width: number;
  height: number;
  exit: string;
}

export interface RoomView extends Room {
  name: string;
  floorId: string;
  type: string;
  capacity?: number;
  currentOccupants?: number;
}

export interface Floor {
  id: string;
  buildingId: string;
  name: string;
  level: number;
  rooms: RoomView[];
}

export interface BuildingView extends Omit<Building, 'floors'> {
  floors: Floor[];
  status: 'SAFE' | 'WARNING' | 'DANGER' | 'EVACUATING';
  totalOccupants?: number;
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
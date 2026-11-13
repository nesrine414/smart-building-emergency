
export interface Building {
  id: string;
  name: string;
  address: string;
  floors: BuildingFloor[];
}

export interface BuildingFloor {
  id: string;
  name: string;
  rooms: BuildingRoom[];
}

export interface BuildingRoom {
  id: string;
  name: string;
  type: 'OFFICE' | 'HALLWAY' | 'EXIT' | 'STAIRCASE' | 'OTHER';
}

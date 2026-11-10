
export interface Incident {
  id: string;
  buildingId: string;
  roomId: string;
  sensorId: string;
  type: 'FIRE' | 'GAS_LEAK';
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
  description: string;
  occupied: boolean;
  timestamp: number;
  updatedAt: number;
  affectedRoomIds: string[];
  recommendation: string;
}

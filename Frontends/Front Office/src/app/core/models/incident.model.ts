export type IncidentStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
export type IncidentSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface Incident {
  id: string;
  buildingId: string;
  roomId: string;
  sensorId: string;
  type: string;
  severity: IncidentSeverity;
  status: IncidentStatus;
  description: string;
  occupied: boolean;
  timestamp: number;
  updatedAt: number;
  affectedRoomIds: string[];
  recommendation?: string;

  // Compatibilite temporaire avec les anciens composants
  title?: string;
  priority?: IncidentSeverity;
  buildingName?: string;
  floorName?: string;
  roomName?: string;
  reportedBy?: string;
  reportedAt?: number;
  resolvedAt?: number;
  alerts?: string[];
  assignedTo?: string[];
  notes?: string[];
}
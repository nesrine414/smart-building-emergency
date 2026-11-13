export type IncidentStatus = 'REPORTED' | 'IN_PROGRESS' | 'CONTAINED' | 'RESOLVED' | 'CLOSED';
export type IncidentPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface Incident {
  id: string;
  title: string;
  description: string;
  status: IncidentStatus;
  priority: IncidentPriority;
  type: string;
  buildingId: string;
  buildingName: string;
  floorId?: string;
  floorName?: string;
  roomId?: string;
  roomName?: string;
  reportedBy: string;
  reportedAt: string;
  updatedAt: string;
  resolvedAt?: string;
  alerts: string[];
  assignedTo?: string[];
  notes?: string[];
}

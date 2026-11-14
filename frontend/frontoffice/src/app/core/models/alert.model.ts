export type AlertType = 'FIRE' | 'GAS_LEAK' | 'SMOKE' | 'EARTHQUAKE' | 'FLOOD' | 'INTRUSION';
export type AlertSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type AlertStatus = 'ACTIVE' | 'ACKNOWLEDGED' | 'RESOLVED' | 'FALSE_ALARM';

export interface Alert {
  id: string;
  type: AlertType;
  severity: AlertSeverity;
  status: AlertStatus;
  title: string;
  description: string;
  buildingId: string;
  buildingName: string;
  floorId?: string;
  floorName?: string;
  roomId?: string;
  roomName?: string;
  latitude?: number;
  longitude?: number;
  triggeredAt: string;
  acknowledgedAt?: string;
  resolvedAt?: string;
  sensorId?: string;
  recommendations?: string[];
}

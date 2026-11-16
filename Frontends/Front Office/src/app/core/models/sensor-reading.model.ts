export type SensorReadingType =
  | 'GAS'
  | 'SMOKE'
  | 'TEMPERATURE'
  | 'HUMIDITY'
  | 'MOTION';

export interface SensorReading {
  id: string;
  buildingId: string;
  roomId: string;
  sensorId: string;
  type: SensorReadingType;
  timestamp: number;
  value: number;
  unit: string;
}
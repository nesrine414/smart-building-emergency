
export interface SensorReading {
  id?: string;
  sensorId: string;
  type: 'GAS' | 'SMOKE' | 'TEMPERATURE' | 'HUMIDITY' | 'MOTION';
  timestamp: number;
  value: number;
  unit: string;
}

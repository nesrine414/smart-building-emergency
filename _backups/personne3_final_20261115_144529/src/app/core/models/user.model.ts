export type UserRole = 'OCCUPANT' | 'ADMIN' | 'FIREFIGHTER';

export interface User {
  id: string;
  username: string;
  email?: string;
  firstName: string;
  lastName: string;
  role: UserRole;
  buildingId?: string;
  floorId?: string;
  roomId?: string;
  phone?: string;
  avatarUrl?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  role: UserRole;
  name: string;
  expiresAt: number;
}
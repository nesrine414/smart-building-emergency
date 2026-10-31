export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  role: 'OCCUPANT' | 'ADMIN' | 'FIREFIGHTER';
  buildingId?: string;
  floorId?: string;
  roomId?: string;
  phone?: string;
  avatarUrl?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: User;
}

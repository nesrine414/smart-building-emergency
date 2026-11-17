export type NotificationType = 'ALERT' | 'INCIDENT' | 'RECOMMENDATION' | 'SYSTEM' | 'AI_INSIGHT';

export interface AppNotification {
  id: string;
  type: NotificationType;
  title: string;
  message: string;
  read: boolean;
  createdAt: string;
  link?: string;
  icon?: string;
  severity?: 'INFO' | 'WARNING' | 'DANGER';
}

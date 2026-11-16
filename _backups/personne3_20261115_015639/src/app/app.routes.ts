import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { Shell } from './shared/components/shell/shell';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login').then(m => m.Login)
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard').then(m => m.Dashboard)
      },
      {
        path: 'alerts',
        loadComponent: () => import('./features/alerts/alerts').then(m => m.Alerts)
      },
      {
        path: 'incidents',
        loadComponent: () => import('./features/incidents/incidents').then(m => m.Incidents)
      },
      {
        path: 'building-map',
        loadComponent: () => import('./features/building-map/building-map').then(m => m.BuildingMap)
      },
      {
        path: 'safety-status',
        loadComponent: () => import('./features/safety-status/safety-status').then(m => m.SafetyStatus)
      },
      {
        path: 'ai-assistant',
        loadComponent: () => import('./features/ai-assistant/ai-assistant').then(m => m.AiAssistant)
      },
      {
        path: 'notifications',
        loadComponent: () => import('./features/notifications/notifications').then(m => m.Notifications)
      },
      {
        path: 'profile',
        loadComponent: () => import('./features/profile/profile').then(m => m.Profile)
      },
      {
        path: '',
        redirectTo: 'dashboard',
        pathMatch: 'full'
      }
    ]
  },
  {
    path: '**',
    redirectTo: 'login'
  }
];

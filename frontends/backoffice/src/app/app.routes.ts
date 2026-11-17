import { Routes } from '@angular/router';
import { adminGuard } from './core/auth/admin.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./auth/login').then(module => module.LoginComponent)
  },
  {
    path: 'dashboard',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./dashboard/dashboard').then(module => module.Dashboard)
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'dashboard'
  },
  {
    path: '**',
    redirectTo: 'dashboard'
  }
];

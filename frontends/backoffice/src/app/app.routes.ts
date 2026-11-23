
import { Routes } from '@angular/router';
import { adminGuard } from './core/auth/admin.guard';

export const routes: Routes = [

  // Connexion : accessible sans authentification.
  {
    path: 'login',
    loadComponent: () =>
      import('./auth/login').then(
        module => module.LoginComponent
      )
  },

  // Callback OAuth2 : obligatoirement public.
  // Ne jamais ajouter adminGuard ici.
  {
    path: 'callback',
    loadComponent: () =>
      import('./auth/callback').then(
        module => module.CallbackComponent
      )
  },

  // Tableau de bord : accès protégé.
  {
    path: 'dashboard',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./dashboard/dashboard').then(
        module => module.Dashboard
      )
  },

  // Page d'accueil.
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'dashboard'
  },

  // Routes inconnues.
  {
    path: '**',
    redirectTo: 'login'
  }
];

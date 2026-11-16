
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (!authService.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }

  if (authService.user()?.role !== 'OCCUPANT') {
    return router.createUrlTree(['/login'], {
      queryParams: { reason: 'forbidden' }
    });
  }

  return true;
};

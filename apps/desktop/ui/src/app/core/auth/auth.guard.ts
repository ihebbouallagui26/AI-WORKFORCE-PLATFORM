import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** Protects the application: unauthenticated users are sent to the login page. */
export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(SessionService);
  const router = inject(Router);
  await session.whenReady();
  if (session.isAuthenticated()) {
    return true;
  }
  const returnUrl = state.url && state.url !== '/' ? state.url : null;
  return router.createUrlTree(['/login'], returnUrl ? { queryParams: { returnUrl } } : {});
};

/** The login page is only for signed-out users. */
export const signedOutGuard: CanActivateFn = async () => {
  const session = inject(SessionService);
  await session.whenReady();
  return session.isAuthenticated() ? inject(Router).createUrlTree(['/dashboard']) : true;
};

/** Only accepts in-app relative URLs as post-login destinations (no open redirect). */
export function safeReturnUrl(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/login') || value.includes('\\')) {
    return '/dashboard';
  }
  return value;
}

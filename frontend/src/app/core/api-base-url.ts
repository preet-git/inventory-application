import { InjectionToken } from '@angular/core';

/**
 * Where the API lives, decided when the container starts rather than when the bundle is built.
 *
 * <p>A built Angular bundle is static, so baking the URL in would mean one image per environment.
 * Instead `env.js` -- a tiny file the frontend container rewrites from `API_BASE_URL` on boot --
 * sets it, and this token reads it. The literal default is what the dev server uses.
 */
declare global {
  interface Window {
    __env?: { apiBaseUrl?: string };
  }
}

export const DEFAULT_API_BASE_URL = 'http://localhost:8080/api';

export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => window.__env?.apiBaseUrl?.trim() || DEFAULT_API_BASE_URL,
});

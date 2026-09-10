import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // fetch rather than XHR: it is the supported transport going forward, and the upload here is
    // a plain POST with no need for XHR-only progress events.
    provideHttpClient(withFetch()),
  ],
};

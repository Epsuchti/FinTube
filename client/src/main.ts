import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { AppComponent } from './app/app.component';
import { provideApi } from './app/api/provide-api';

bootstrapApplication(AppComponent, { providers: [provideHttpClient(), provideApi('/api')] })
  .catch((error: unknown) => console.error(error));

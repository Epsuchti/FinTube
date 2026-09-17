import {ChangeDetectionStrategy, Component, OnInit, ViewEncapsulation, inject, signal} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {AccountService, AuthenticationService, Profile, SetupService} from './api';
import {SubscriptionsPageComponent} from './pages/subscriptions-page/subscriptions-page.component';
import {VideosPageComponent} from './pages/videos-page/videos-page.component';
import {DownloadsPageComponent} from './pages/downloads-page/downloads-page.component';
import {AccountPageComponent} from './pages/account-page/account-page.component';
import {AdminUsersPageComponent} from './pages/admin-users-page/admin-users-page.component';
import {AdminSettingsPageComponent} from './pages/admin-settings-page/admin-settings-page.component';
import {AdminJellyfinPageComponent} from './pages/admin-jellyfin-page/admin-jellyfin-page.component';
import {AdminCachePageComponent} from './pages/admin-cache-page/admin-cache-page.component';
import {AdminJobsPageComponent} from './pages/admin-jobs-page/admin-jobs-page.component';
import {AuthPageComponent} from './pages/auth-page/auth-page.component';
import {InitialSetupPageComponent} from './pages/initial-setup-page/initial-setup-page.component';

type Section =
    | 'subscriptions'
    | 'videos'
    | 'downloads'
    | 'account'
    | 'users'
    | 'settings'
    | 'jellyfin'
    | 'cache'
    | 'jobs';

@Component({
    selector: 'app-root',
    imports: [
        CommonModule,
        SubscriptionsPageComponent,
        VideosPageComponent,
        DownloadsPageComponent,
        AccountPageComponent,
        AdminUsersPageComponent,
        AdminSettingsPageComponent,
        AdminJellyfinPageComponent,
        AdminCachePageComponent,
        AdminJobsPageComponent,
        AuthPageComponent,
        InitialSetupPageComponent
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
    templateUrl: './app.component.html',
    styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
    private readonly accountApi = inject(AccountService);
    private readonly authenticationApi = inject(AuthenticationService);
    private readonly setupApi = inject(SetupService);

    readonly me = signal<Profile | null>(null);
    readonly setupRequired = signal(false);
    readonly section = signal<Section>('subscriptions');
    readonly videoCount = signal(0);
    readonly loading = signal(false);
    readonly notice = signal('');
    readonly error = signal('');

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.accountApi.getCurrentUser().subscribe({
            next: profile => {
                this.me.set(profile);
                this.setupRequired.set(false);
                this.loading.set(false);
                this.clearMessages();
            },
            error: (error: unknown) => {
                this.me.set(null);
                this.loading.set(false);
                if (this.isUnauthenticated(error)) {
                    this.checkSetupStatus();
                } else {
                    this.error.set(this.messageFor(error, 'Unable to load the current account.'));
                }
            }
        });
    }

    logout(): void {
        this.loading.set(true);
        this.authenticationApi.logout().subscribe({
            next: () => {
                this.me.set(null);
                this.videoCount.set(0);
                this.loading.set(false);
                this.setSection('subscriptions');
                this.notice.set('Signed out.');
            },
            error: (error: unknown) => {
                this.me.set(null);
                this.videoCount.set(0);
                this.loading.set(false);
                this.setSection('subscriptions');
                this.error.set(this.messageFor(error, 'Unable to sign out.'));
            }
        });
    }

    setSection(value: Section): void {
        this.section.set(value);
    }

    clearMessages(): void {
        this.error.set('');
        this.notice.set('');
    }

    private checkSetupStatus(): void {
        this.setupApi.getSetupStatus().subscribe({
            next: status => this.setupRequired.set(status.setupRequired),
            error: (error: unknown) => this.error.set(this.messageFor(error, 'Unable to determine setup status.'))
        });
    }

    private isUnauthenticated(error: unknown): boolean {
        const status = error instanceof HttpErrorResponse
            ? error.status
            : (error as {status?: unknown} | null)?.status;
        return status === 401 || status === 404;
    }

    private messageFor(error: unknown, fallback: string): string {
        if (error instanceof HttpErrorResponse) {
            const body = error.error as { message?: string } | string | null;
            if (typeof body === 'string' && body.trim()) return body;
            if (body && typeof body === 'object' && typeof body.message === 'string' && body.message.trim()) return body.message;
        }
        return fallback;
    }
}

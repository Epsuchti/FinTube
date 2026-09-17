import {ChangeDetectionStrategy, Component, EventEmitter, OnInit, Output, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {AdminService} from '../../api';

interface JellyfinStatus {
    enabled: boolean;
    configured: boolean;
    reachable: boolean;
    apiKeyConfigured: boolean;
    statusCode?: number;
    baseUrl?: string;
    serverName?: string;
    version?: string;
    message?: string;
    refreshCount?: number;
    lastRefreshAt?: string;
    lastRefreshSuccess?: boolean;
    lastRefreshMessage?: string;
    runtimeSuccessCount?: number;
    runtimeFailureCount?: number;
    pendingRuntimeSyncs?: number;
    lastRuntimeSyncAt?: string;
    lastRuntimeSyncSuccess?: boolean;
    lastRuntimeSyncMessage?: string;
}

@Component({
    selector: 'ft-admin-jellyfin-page',
    standalone: true,
    imports: [CommonModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './admin-jellyfin-page.component.html'
})
export class AdminJellyfinPageComponent implements OnInit {
    private readonly adminApi = inject(AdminService);

    readonly status = signal<JellyfinStatus | null>(null);
    readonly configuredUrl = signal('');
    readonly loading = signal(false);
    readonly saving = signal(false);
    readonly error = signal('');
    readonly notice = signal('');
    @Output() editSettings = new EventEmitter<void>();

    ngOnInit(): void {
        this.loadStatus(true);
        this.loadSettings();
    }

    validate(): void {
        this.saving.set(true);
        this.adminApi.validateJellyfin().subscribe({
            next: value => {
                const status = value as JellyfinStatus;
                this.status.set(status);
                this.saving.set(false);
                this.notice.set(status.reachable ? 'Jellyfin connection validated.' : `Jellyfin validation failed: ${status.message || 'server unreachable'}`);
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not validate Jellyfin connection.'));
            }
        });
    }

    refresh(): void {
        this.saving.set(true);
        this.adminApi.refreshJellyfin().subscribe({
            next: result => {
                this.saving.set(false);
                this.notice.set(result.success ? 'Jellyfin library refresh requested.' : `Jellyfin refresh failed: ${result.message || 'unknown error'}`);
                this.loadStatus(false);
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not request Jellyfin library refresh.'));
            }
        });
    }

    private loadStatus(showLoading: boolean): void {
        if (showLoading) this.loading.set(true);
        this.adminApi.getJellyfinStatus().subscribe({
            next: value => {
                this.status.set(value as JellyfinStatus);
                if (showLoading) this.loading.set(false);
            },
            error: (error: unknown) => {
                if (showLoading) this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load Jellyfin status.'));
            }
        });
    }

    private loadSettings(): void {
        this.adminApi.getSettings().subscribe({
            next: settings => this.configuredUrl.set(settings['jellyfin_url'] ?? ''),
            error: (error: unknown) => this.error.set(this.messageFor(error, 'Could not load Jellyfin settings.'))
        });
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

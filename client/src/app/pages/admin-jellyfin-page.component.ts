import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';

export interface JellyfinPageStatus {
    enabled: boolean;
    reachable: boolean;
    message?: string;
    baseUrl?: string;
    apiKeyConfigured?: boolean;
    serverName?: string;
    version?: string;
    statusCode?: number;
    refreshCount?: number;
    lastRefreshAt?: string;
    lastRefreshSuccess?: boolean;
    lastRefreshMessage?: string;
    runtimeSuccessCount?: number;
    pendingRuntimeSyncs?: number;
    runtimeFailureCount?: number;
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
export class AdminJellyfinPageComponent {
    @Input() status: JellyfinPageStatus | null = null;
    @Input() saving = false;
    @Input() configuredUrl = '';
    @Output() validate = new EventEmitter<void>();
    @Output() refresh = new EventEmitter<void>();
    @Output() editSettings = new EventEmitter<void>();
}

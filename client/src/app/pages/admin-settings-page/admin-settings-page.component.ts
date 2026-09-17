import {ChangeDetectionStrategy, Component, OnInit, inject, signal} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {AdminService} from '../../api';

interface SettingGroup {
    title: string;
    keys: string[];
}

@Component({
    selector: 'ft-admin-settings-page',
    standalone: true,
    imports: [CommonModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './admin-settings-page.component.html'
})
export class AdminSettingsPageComponent implements OnInit {
    private readonly adminApi = inject(AdminService);

    readonly secretSettings = new Set([
        'youtube_api_key',
        'jellyfin_api_key',
        'proxy_password',
        'cookie_file',
        'youtube_po_token',
        'youtube_po_token_provider_args'
    ]);
    readonly settingGroups: SettingGroup[] = [
        {
            title: 'Playback',
            keys: ['stream_quality', 'allowed_video_codecs', 'preferred_video_codecs', 'allowed_audio_codecs', 'preferred_audio_codecs', 'public_base_url']
        },
        {
            title: 'Cache',
            keys: ['cache_retention_days', 'cache_min_free_gb', 'background_download_max_mbps', 'newest_videos_to_download']
        },
        {
            title: 'YouTube',
            keys: ['youtube_api_key', 'initial_channel_import_count', 'subscription_sync_minutes', 'youtube_player_client', 'youtube_po_token_provider_enabled', 'youtube_po_token', 'youtube_po_token_provider_args']
        },
        {
            title: 'Jellyfin',
            keys: ['jellyfin_enabled', 'jellyfin_url', 'jellyfin_api_key', 'jellyfin_auto_refresh', 'jellyfin_runtime_sync', 'jellyfin_request_timeout_seconds']
        },
        {
            title: 'Network and yt-dlp',
            keys: ['yt_dlp_path', 'ffmpeg_path', 'proxy_url', 'proxy_username', 'proxy_password', 'cookie_file']
        }
    ];
    readonly settings = signal<Record<string, string>>({});
    readonly loading = signal(false);
    readonly saving = signal(false);
    readonly error = signal('');
    readonly notice = signal('');

    ngOnInit(): void {
        this.loadSettings();
    }

    value(key: string): string {
        return this.settings()[key] ?? '';
    }

    setSetting(key: string, value: string): void {
        this.settings.update(current => ({...current, [key]: value}));
    }

    save(): void {
        this.saving.set(true);
        this.adminApi.updateSettings({requestBody: this.settings()}).subscribe({
            next: () => {
                this.saving.set(false);
                this.notice.set('Global settings saved. Secret values remain masked.');
                this.loadSettings();
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not save settings.'));
            }
        });
    }

    private loadSettings(): void {
        this.loading.set(true);
        this.adminApi.getSettings().subscribe({
            next: settings => {
                this.settings.set(settings);
                this.loading.set(false);
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load settings.'));
            }
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

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

    readonly streamQualityOptions = [
        {value: '480', label: '480p'},
        {value: '720', label: '720p'},
        {value: '1080', label: '1080p'},
        {value: '1440', label: '1440p'},
        {value: '2160', label: '2160p (4K)'},
        {value: 'best-compatible', label: 'Best compatible'},
        {value: 'best', label: 'Best available'}
    ];

    readonly settingLabels: Record<string, string> = {
        stream_quality: 'Stream quality',
        allowed_video_codecs: 'Allowed video codecs',
        preferred_video_codecs: 'Preferred video codecs',
        allowed_audio_codecs: 'Allowed audio codecs',
        preferred_audio_codecs: 'Preferred audio codecs',
        public_base_url: 'Public base URL',
        cache_retention_days: 'Cache retention (days)',
        cache_min_free_gb: 'Minimum free cache space (GB)',
        background_download_max_mbps: 'Background download limit (Mbps)',
        youtube_api_key: 'YouTube Data API key',
        initial_channel_import_count: 'Initial channel import count',
        subscription_sync_minutes: 'Subscription sync interval (minutes)',
        youtube_player_client: 'YouTube player client',
        youtube_po_token_provider_enabled: 'PO token provider enabled',
        youtube_po_token: 'Manual YouTube PO token (optional)',
        jellyfin_enabled: 'Jellyfin enabled',
        jellyfin_url: 'Jellyfin URL',
        jellyfin_api_key: 'Jellyfin API key',
        jellyfin_auto_refresh: 'Jellyfin automatic refresh',
        jellyfin_runtime_sync: 'Jellyfin runtime sync',
        jellyfin_request_timeout_seconds: 'Jellyfin request timeout (seconds)',
        yt_dlp_path: 'yt-dlp path',
        ffmpeg_path: 'FFmpeg path',
        proxy_url: 'Proxy URL',
        proxy_username: 'Proxy username',
        proxy_password: 'Proxy password',
        cookie_file: 'Cookie file'
    };

    readonly secretSettings = new Set([
        'youtube_api_key',
        'jellyfin_api_key',
        'proxy_password',
        'cookie_file',
        'youtube_po_token'
    ]);
    readonly settingGroups: SettingGroup[] = [
        {
            title: 'Playback',
            keys: ['stream_quality', 'allowed_video_codecs', 'preferred_video_codecs', 'allowed_audio_codecs', 'preferred_audio_codecs', 'public_base_url']
        },
        {
            title: 'Cache',
            keys: ['cache_retention_days', 'cache_min_free_gb', 'background_download_max_mbps']
        },
        {
            title: 'YouTube',
            keys: ['youtube_api_key', 'initial_channel_import_count', 'subscription_sync_minutes', 'youtube_player_client', 'youtube_po_token_provider_enabled', 'youtube_po_token']
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

    labelFor(key: string): string {
        return this.settingLabels[key] ?? key;
    }

    helpFor(key: string): string {
        if (key === 'stream_quality') return 'Maximum video height for the shared YouTube source. Best compatible allows codec filtering to choose the highest playable option.';
        if (key === 'cache_min_free_gb') return 'The cache evicts the oldest inactive fragments when the filesystem drops below this free-space reserve.';
        if (key === 'background_download_max_mbps') return 'Maximum rate for low-priority prefetch jobs. Playback traffic is not limited by this setting.';
        if (key === 'youtube_po_token_provider_enabled') return 'Leave enabled. The provider address is configured by the deployment, not here.';
        if (key === 'youtube_po_token') return 'Usually leave blank. Use only a manually generated CLIENT.CONTEXT+TOKEN value when the automatic provider is unavailable.';
        return this.secretSettings.has(key) ? 'Secret · stored server-side and never shown to normal users' : 'Administrator-controlled global value';
    }

    isStreamQuality(key: string): boolean {
        return key === 'stream_quality';
    }

    isBoolean(key: string): boolean {
        return key === 'youtube_po_token_provider_enabled' || key === 'jellyfin_enabled' || key === 'jellyfin_auto_refresh' || key === 'jellyfin_runtime_sync';
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

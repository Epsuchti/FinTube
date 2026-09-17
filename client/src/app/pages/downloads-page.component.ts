import {ChangeDetectionStrategy, Component, Input} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';

export interface DownloadPageItem {
    video_id: string;
    title: string;
    channel?: string | null;
    cache_status?: string;
    cache_last_accessed_at?: string | null;
    cache_expires_at?: string | null;
    cache_bytes?: number;
    cached_fragments?: number;
    downloaded?: number | boolean;
}

@Component({
    selector: 'ft-downloads-page',
    standalone: true,
    imports: [CommonModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './downloads-page.component.html'
})
export class DownloadsPageComponent {
    @Input({required: true}) videos: DownloadPageItem[] = [];

    downloaded(video: DownloadPageItem): boolean {
        return video.downloaded === true || video.downloaded === 1 || video.cache_status === 'COMPLETE';
    }

    formatBytes(bytes?: number): string {
        if (!bytes) return '—';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
        return `${(bytes / 1024 ** exponent).toFixed(exponent ? 1 : 0)} ${units[exponent]}`;
    }
}

import {ChangeDetectionStrategy, Component, OnInit, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {LibraryService, Video} from '../../api';

@Component({
    selector: 'ft-downloads-page',
    standalone: true,
    imports: [CommonModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './downloads-page.component.html'
})
export class DownloadsPageComponent implements OnInit {
    private readonly libraryApi = inject(LibraryService);

    readonly videos = signal<Video[]>([]);
    readonly loading = signal(false);
    readonly error = signal('');
    readonly notice = signal('');

    ngOnInit(): void {
        this.loadVideos();
    }

    downloaded(video: Video): boolean {
        return video.downloaded === true || (video.downloaded as unknown) === 1 || video.cache_status === 'COMPLETE';
    }

    formatBytes(bytes?: number): string {
        if (!bytes) return '—';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
        return `${(bytes / 1024 ** exponent).toFixed(exponent ? 1 : 0)} ${units[exponent]}`;
    }

    private loadVideos(): void {
        this.loading.set(true);
        this.libraryApi.listVideos().subscribe({
            next: rows => {
                this.videos.set(rows);
                this.loading.set(false);
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load downloaded videos.'));
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

import {ChangeDetectionStrategy, Component, EventEmitter, OnInit, Output, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {LibraryService, Video} from '../../api';

@Component({
    selector: 'ft-videos-page',
    standalone: true,
    imports: [CommonModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './videos-page.component.html'
})
export class VideosPageComponent implements OnInit {
    private readonly libraryApi = inject(LibraryService);

    readonly videos = signal<Video[]>([]);
    readonly loading = signal(false);
    readonly error = signal('');
    readonly notice = signal('');
    @Output() manageChannels = new EventEmitter<void>();
    @Output() videoCountChange = new EventEmitter<number>();

    ngOnInit(): void {
        this.loadVideos();
    }

    formatDuration(seconds?: number | null): string {
        if (!seconds) return 'Duration unavailable';
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        const remaining = seconds % 60;
        return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(remaining).padStart(2, '0')}` : `${minutes}:${String(remaining).padStart(2, '0')}`;
    }

    playbackUrl(video: Video): string {
        return `/play/${encodeURIComponent(video.video_id)}`;
    }

    private loadVideos(): void {
        this.loading.set(true);
        this.libraryApi.listVideos().subscribe({
            next: rows => {
                this.videos.set(rows);
                this.videoCountChange.emit(rows.length);
                this.loading.set(false);
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load videos.'));
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

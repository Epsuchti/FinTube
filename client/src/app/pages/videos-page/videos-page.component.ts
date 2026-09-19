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
    readonly searchQuery = signal('');
    readonly currentPage = signal(1);
    readonly pageSize = 100;
    readonly totalVideos = signal(0);
    readonly totalPages = signal(0);
    private requestSequence = 0;
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

    search(value: string): void {
        this.searchQuery.set(value.trim());
        this.currentPage.set(1);
        this.loadVideos();
    }

    goToPage(page: number): void {
        this.currentPage.set(Math.min(Math.max(page, 1), Math.max(this.totalPages(), 1)));
        this.loadVideos();
    }

    pageNumbers(): number[] {
        const pageCount = this.totalPages();
        if (pageCount <= 1) return [];
        const start = Math.max(1, Math.min(this.currentPage() - 2, pageCount - 4));
        const end = Math.min(pageCount, start + 4);
        return Array.from({length: end - start + 1}, (_, index) => start + index);
    }

    downloaded(video: Video): boolean {
        return video.downloaded === true || video.cache_status === 'COMPLETE';
    }

    formatBytes(bytes?: number): string {
        if (!bytes) return '—';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
        return `${(bytes / 1024 ** exponent).toFixed(exponent ? 1 : 0)} ${units[exponent]}`;
    }

    thumbnailError(event: Event): void {
        (event.target as HTMLImageElement).hidden = true;
    }

    private loadVideos(): void {
        const requestSequence = ++this.requestSequence;
        this.loading.set(true);
        this.libraryApi.listVideos({
            page: this.currentPage(),
            pageSize: this.pageSize,
            search: this.searchQuery() || undefined
        }).subscribe({
            next: result => {
                if (requestSequence !== this.requestSequence) return;
                this.videos.set(result.items);
                this.currentPage.set(result.page);
                this.totalVideos.set(result.total_elements);
                this.totalPages.set(result.total_pages);
                if (!this.searchQuery()) this.videoCountChange.emit(result.total_elements);
                this.loading.set(false);
            },
            error: (error: unknown) => {
                if (requestSequence !== this.requestSequence) return;
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load videos.'));
            }
        });
    }

    private messageFor(error: unknown, fallback: string): string {
        if (error instanceof HttpErrorResponse) {
            const body = error.error as { detail?: string; message?: string } | string | null;
            if (typeof body === 'string' && body.trim()) return body;
            if (body && typeof body === 'object' && typeof body.detail === 'string' && body.detail.trim()) return body.detail;
            if (body && typeof body === 'object' && typeof body.message === 'string' && body.message.trim()) return body.message;
        }
        return fallback;
    }
}

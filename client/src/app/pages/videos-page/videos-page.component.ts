import {ChangeDetectionStrategy, Component, ElementRef, EventEmitter, OnDestroy, OnInit, Output, ViewChild, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import Hls from 'hls.js';
import {LibraryService, Video} from '../../api';

@Component({
    selector: 'ft-videos-page',
    standalone: true,
    imports: [CommonModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './videos-page.component.html'
})
export class VideosPageComponent implements OnDestroy, OnInit {
    private readonly libraryApi = inject(LibraryService);
    private hls?: Hls;
    @ViewChild('player') private player?: ElementRef<HTMLVideoElement>;

    readonly videos = signal<Video[]>([]);
    readonly loading = signal(false);
    readonly error = signal('');
    readonly notice = signal('');
    readonly playbackError = signal('');
    readonly playingVideoId = signal<string | null>(null);
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

    play(video: Video): void {
        const closing = this.playingVideoId() === video.video_id;
        this.stopPlayer();
        this.playbackError.set('');
        this.playingVideoId.set(closing ? null : video.video_id);
        if (!closing) setTimeout(() => this.startPlayer(video));
    }

    thumbnailError(event: Event): void {
        (event.target as HTMLImageElement).hidden = true;
    }

    playerError(): void {
        this.playbackError.set('Playback failed. Verify that yt-dlp and ffmpeg are installed on the server.');
    }

    ngOnDestroy(): void {
        this.stopPlayer();
    }

    private startPlayer(video: Video): void {
        const element = this.player?.nativeElement;
        if (!element || this.playingVideoId() !== video.video_id) return;
        const source = this.playbackUrl(video);
        if (element.canPlayType('application/vnd.apple.mpegurl')) {
            element.src = source;
            void element.play().catch(() => undefined);
            return;
        }
        if (!Hls.isSupported()) {
            this.playbackError.set('This browser cannot play HLS video.');
            return;
        }
        this.hls = new Hls();
        this.hls.loadSource(source);
        this.hls.attachMedia(element);
        this.hls.on(Hls.Events.MANIFEST_PARSED, () => void element.play().catch(() => undefined));
        this.hls.on(Hls.Events.ERROR, (_event, data) => {
            if (data.fatal) this.playerError();
        });
    }

    private stopPlayer(): void {
        this.hls?.destroy();
        this.hls = undefined;
        const element = this.player?.nativeElement;
        if (element) {
            element.pause();
            element.removeAttribute('src');
            element.load();
        }
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

import {ChangeDetectionStrategy, Component, OnInit, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {AdminService, CacheEntry} from '../../api';

@Component({
    selector: 'ft-admin-cache-page',
    standalone: true,
    imports: [CommonModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './admin-cache-page.component.html'
})
export class AdminCachePageComponent implements OnInit {
    private readonly adminApi = inject(AdminService);

    readonly entries = signal<CacheEntry[]>([]);
    readonly loading = signal(false);
    readonly saving = signal(false);
    readonly error = signal('');
    readonly notice = signal('');

    ngOnInit(): void {
        this.loadEntries();
    }

    delete(entry: CacheEntry): void {
        if (!window.confirm(`Delete cached media for ${entry.video_id}?`)) return;
        this.saving.set(true);
        this.adminApi.deleteCacheEntry({video: entry.video_id}).subscribe({
            next: () => {
                this.saving.set(false);
                this.notice.set('Cache entry deleted.');
                this.loadEntries();
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not delete cache entry.'));
            }
        });
    }

    private loadEntries(): void {
        this.loading.set(true);
        this.adminApi.listCacheEntries().subscribe({
            next: entries => {
                this.entries.set(entries);
                this.loading.set(false);
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load cache status.'));
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

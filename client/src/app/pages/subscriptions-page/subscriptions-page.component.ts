import {ChangeDetectionStrategy, Component, EventEmitter, OnInit, Output, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {LibraryService, Subscription} from '../../api';
import {switchMap} from 'rxjs';

@Component({
    selector: 'ft-subscriptions-page',
    standalone: true,
    imports: [CommonModule, FormsModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './subscriptions-page.component.html'
})
export class SubscriptionsPageComponent implements OnInit {
    private readonly libraryApi = inject(LibraryService);

    readonly subscriptions = signal<Subscription[]>([]);
    readonly loading = signal(false);
    readonly error = signal('');
    readonly notice = signal('');
    readonly importCounts = signal<Record<number, number>>({});
    readonly downloadCounts = signal<Record<number, number>>({});
    @Output() videoCountChange = new EventEmitter<number>();
    channel = '';

    ngOnInit(): void {
        this.loadSubscriptions();
        this.loadVideoCount();
    }

    addSubscription(): void {
        const value = this.channel.trim();
        if (!value) {
            this.error.set('Enter a YouTube channel URL, channel ID, or @handle.');
            return;
        }
        this.channel = '';
        this.loading.set(true);
        this.libraryApi.addSubscription({addSubscriptionRequest: {channel: value}}).subscribe({
            next: () => {
                this.notice.set('Channel added. Refresh it to discover videos.');
                this.loadSubscriptions();
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not add subscription.'));
            }
        });
    }

    updateSubscription(subscription: Subscription): void {
        const enabled = !this.enabled(subscription);
        this.loading.set(true);
        this.libraryApi.updateSubscription({
            id: subscription.id,
            toggleSubscriptionRequest: {enabled}
        }).subscribe({
            next: () => {
                this.notice.set(enabled ? 'Subscription enabled.' : 'Subscription paused.');
                this.loadSubscriptions();
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not update subscription.'));
            }
        });
    }

    refreshSubscription(subscription: Subscription): void {
        this.loading.set(true);
        this.libraryApi.refreshSubscription({id: subscription.id}).subscribe({
            next: result => {
                this.notice.set(`Refresh complete${result.discovered === undefined ? '.' : `: ${result.discovered} new video(s) discovered.`}`);
                this.loadSubscriptions();
                this.loadVideoCount();
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not refresh subscription.'));
            }
        });
    }

    reimportSubscription(subscription: Subscription): void {
        const count = this.importCount(subscription);
        if (!Number.isInteger(count) || count < 1 || count > 1000) {
            this.error.set('Initial import count must be between 1 and 1000.');
            return;
        }
        const downloadCount = this.downloadCount(subscription);
        if (!Number.isInteger(downloadCount) || downloadCount < 0 || downloadCount > 1000) {
            this.error.set('Download count must be between 0 and 1000.');
            return;
        }
        this.loading.set(true);
        this.libraryApi.updateSubscription({
            id: subscription.id,
            toggleSubscriptionRequest: {
                enabled: this.enabled(subscription), initial_import_count: count, download_count: downloadCount
            }
        }).pipe(switchMap(() => this.libraryApi.refreshSubscription({id: subscription.id}))).subscribe({
            next: result => {
                this.notice.set(`Reimport complete${result.discovered === undefined ? '.' : `: ${result.discovered} new video(s) discovered.`}`);
                this.loadSubscriptions();
                this.loadVideoCount();
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not reimport subscription.'));
            }
        });
    }

    removeSubscription(subscription: Subscription): void {
        if (!window.confirm(`Remove ${subscription.name} from your subscriptions?`)) return;
        this.loading.set(true);
        this.libraryApi.removeSubscription({id: subscription.id}).subscribe({
            next: () => {
                this.notice.set('Subscription removed. Shared cache is retained for other users.');
                this.loadSubscriptions();
                this.loadVideoCount();
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not remove subscription.'));
            }
        });
    }

    enabled(item: Subscription): boolean {
        return item.enabled === true || (item.enabled as unknown) === 1;
    }

    importCount(subscription: Subscription): number {
        return this.importCounts()[subscription.id] ?? subscription.initial_import_count;
    }

    setImportCount(subscription: Subscription, value: string): void {
        const count = Number(value);
        this.importCounts.update(current => ({...current, [subscription.id]: count}));
    }

    downloadCount(subscription: Subscription): number {
        return this.downloadCounts()[subscription.id] ?? subscription.download_count;
    }

    setDownloadCount(subscription: Subscription, value: string): void {
        const count = Number(value);
        this.downloadCounts.update(current => ({...current, [subscription.id]: count}));
    }

    private loadSubscriptions(): void {
        this.loading.set(true);
        this.libraryApi.listSubscriptions().subscribe({
            next: rows => {
                this.subscriptions.set(rows);
                this.importCounts.set(Object.fromEntries(rows.map(row => [row.id, row.initial_import_count])));
                this.downloadCounts.set(Object.fromEntries(rows.map(row => [row.id, row.download_count])));
                this.loading.set(false);
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load subscriptions.'));
            }
        });
    }

    private loadVideoCount(): void {
        this.libraryApi.listVideos().subscribe({
            next: rows => this.videoCountChange.emit(rows.length)
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

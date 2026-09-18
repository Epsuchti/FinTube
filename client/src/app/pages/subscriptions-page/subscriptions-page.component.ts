import {ChangeDetectionStrategy, Component, EventEmitter, OnInit, Output, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {LibraryService, Subscription} from '../../api';

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

    private loadSubscriptions(): void {
        this.loading.set(true);
        this.libraryApi.listSubscriptions().subscribe({
            next: rows => {
                this.subscriptions.set(rows);
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

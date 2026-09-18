import {ChangeDetectionStrategy, Component, ElementRef, EventEmitter, OnInit, Output, ViewChild, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {LibraryService, Subscription} from '../../api';
import {switchMap} from 'rxjs';

interface CookieImportFile {
    fileName: string;
}

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
    readonly editingId = signal<number | null>(null);
    readonly cookieImportOpen = signal(false);
    readonly cookieImport = signal<CookieImportFile | null>(null);
    @Output() videoCountChange = new EventEmitter<number>();
    @ViewChild('cookieFile') private cookieFile?: ElementRef<HTMLInputElement>;
    private cookieContents = '';
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

    toggleCookieImport(): void {
        this.cookieImportOpen.update(open => !open);
    }

    selectCookieFile(event: Event): void {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        if (!file) return;
        this.error.set('');
        if (file.size > 5_000_000) {
            this.cookieContents = '';
            this.cookieImport.set(null);
            this.resetCookieInput();
            this.error.set('Cookie files must be smaller than 5 MB.');
            return;
        }
        const reader = new FileReader();
        reader.onload = () => {
            const contents = String(reader.result ?? '');
            if (!this.looksLikeNetscapeCookieFile(contents)) {
                this.cookieContents = '';
                this.cookieImport.set(null);
                this.resetCookieInput();
                this.error.set('Choose a Netscape-format YouTube cookies.txt export.');
                return;
            }
            this.cookieContents = contents;
            this.cookieImport.set({fileName: file.name});
        };
        reader.onerror = () => {
            this.cookieContents = '';
            this.cookieImport.set(null);
            this.resetCookieInput();
            this.error.set('Could not read the cookie file.');
        };
        reader.readAsText(file);
    }

    clearCookieImport(): void {
        this.cookieContents = '';
        this.cookieImport.set(null);
        this.resetCookieInput();
    }

    importFromYouTube(): void {
        if (!this.cookieContents) {
            this.error.set('Choose a YouTube cookies.txt file first.');
            return;
        }
        this.loading.set(true);
        this.libraryApi.importSubscriptionsFromCookies({
            importCookiesRequest: {cookies: this.cookieContents}
        }).subscribe({
            next: result => {
                this.clearCookieImport();
                this.notice.set(this.importMessage(result));
                this.loadSubscriptions();
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not import YouTube subscriptions.'));
            }
        });
    }

    editSubscription(subscription: Subscription): void {
        this.editingId.update(current => current === subscription.id ? null : subscription.id);
    }

    cancelEdit(): void {
        this.editingId.set(null);
    }

    saveSubscription(subscription: Subscription): void {
        const values = this.subscriptionSettings(subscription);
        if (!values) return;
        this.loading.set(true);
        this.libraryApi.updateSubscription({
            id: subscription.id,
            toggleSubscriptionRequest: {
                enabled: this.enabled(subscription),
                initial_import_count: values.initialImportCount,
                download_count: values.downloadCount
            }
        }).subscribe({
            next: () => {
                this.editingId.set(null);
                this.notice.set('Subscription settings saved.');
                this.loadSubscriptions();
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not save subscription settings.'));
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
        const values = this.subscriptionSettings(subscription);
        if (!values) return;
        this.loading.set(true);
        this.libraryApi.updateSubscription({
            id: subscription.id,
            toggleSubscriptionRequest: {
                enabled: this.enabled(subscription),
                initial_import_count: values.initialImportCount,
                download_count: values.downloadCount
            }
        }).pipe(switchMap(() => this.libraryApi.refreshSubscription({id: subscription.id}))).subscribe({
            next: result => {
                this.editingId.set(null);
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

    setImportCount(subscription: Subscription, value: string | number): void {
        const count = Number(value);
        this.importCounts.update(current => ({...current, [subscription.id]: count}));
    }

    downloadCount(subscription: Subscription): number {
        return this.downloadCounts()[subscription.id] ?? subscription.download_count;
    }

    setDownloadCount(subscription: Subscription, value: string | number): void {
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
                if (!rows.some(row => row.id === this.editingId())) this.editingId.set(null);
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

    private subscriptionSettings(subscription: Subscription): {initialImportCount: number; downloadCount: number} | null {
        const initialImportCount = this.importCount(subscription);
        if (!Number.isInteger(initialImportCount) || initialImportCount < 1 || initialImportCount > 1000) {
            this.error.set('Initial import count must be between 1 and 1000.');
            return null;
        }
        const downloadCount = this.downloadCount(subscription);
        if (!Number.isInteger(downloadCount) || downloadCount < 0 || downloadCount > 1000) {
            this.error.set('Download count must be between 0 and 1000.');
            return null;
        }
        return {initialImportCount, downloadCount};
    }

    private looksLikeNetscapeCookieFile(value: string): boolean {
        const normalized = value.replace(/^\uFEFF/, '');
        return normalized.split(/\r?\n/).some(line =>
            line.startsWith('# Netscape HTTP Cookie File') || line.startsWith('# HTTP Cookie File'));
    }

    private resetCookieInput(): void {
        if (this.cookieFile) this.cookieFile.nativeElement.value = '';
    }

    private importMessage(result: {imported: number; skipped: number; failed: number}): string {
        const imported = result.imported ?? 0;
        const skipped = result.skipped ?? 0;
        const failed = result.failed ?? 0;
        const details = [`${imported} channel${imported === 1 ? '' : 's'} imported`];
        if (skipped > 0) details.push(`${skipped} already subscribed`);
        if (failed > 0) details.push(`${failed} could not be read`);
        return `${details.join(', ')}.`;
    }
}

import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {FormsModule} from '@angular/forms';

export interface SubscriptionPageItem {
    id: number;
    channel_id: string;
    name: string;
    enabled: boolean | number;
    last_checked_at?: string | null;
}

@Component({
    selector: 'ft-subscriptions-page', standalone: true, imports: [CommonModule, FormsModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './subscriptions-page.component.html'
})
export class SubscriptionsPageComponent {
    @Input({required: true}) subscriptions: SubscriptionPageItem[] = [];
    @Input() loading = false;
    @Output() add = new EventEmitter<string>();
    @Output() toggle = new EventEmitter<SubscriptionPageItem>();
    @Output() refresh = new EventEmitter<SubscriptionPageItem>();
    @Output() remove = new EventEmitter<SubscriptionPageItem>();
    channel = '';

    submit(): void {
        const channel = this.channel.trim();
        if (!channel) return;
        this.add.emit(channel);
        this.channel = '';
    }

    enabled(item: SubscriptionPageItem) {
        return item.enabled === true || item.enabled === 1;
    }
}

import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';

export interface CachePageItem {
    video_id: string;
    format_key?: string;
    status: string;
    last_accessed_at?: string;
    active_readers?: number;
    active_writers?: number;
}

@Component({
    selector: 'ft-admin-cache-page',
    standalone: true,
    imports: [CommonModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './admin-cache-page.component.html'
})
export class AdminCachePageComponent {
    @Input({required: true}) entries: CachePageItem[] = [];
    @Output() delete = new EventEmitter<CachePageItem>();
}

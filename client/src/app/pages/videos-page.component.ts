import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';

export interface VideoPageItem {
  video_id: string;
  title: string;
  channel?: string | null;
  published_at?: string | null;
  duration_seconds?: number | null;
}

@Component({
  selector: 'ft-videos-page',
  standalone: true,
  imports: [CommonModule, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './videos-page.component.html'
})
export class VideosPageComponent {
  @Input({required: true}) videos: VideoPageItem[] = [];
  @Input({required: true}) playbackUrl!: (video: VideoPageItem) => string;
  @Output() manageChannels = new EventEmitter<void>();

  formatDuration(seconds?: number | null): string {
    if (!seconds) return 'Duration unavailable';
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const remaining = seconds % 60;
    return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(remaining).padStart(2, '0')}` : `${minutes}:${String(remaining).padStart(2, '0')}`;
  }
}

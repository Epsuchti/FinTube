import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

type Role = 'USER' | 'ADMIN';
type Section = 'subscriptions' | 'videos' | 'downloads' | 'account' | 'users' | 'settings' | 'jellyfin' | 'cache' | 'jobs';

interface Profile { id: number; username: string; role: Role; filesystemSlug: string; }
interface Subscription { id: number; channel_id: string; name: string; url?: string; enabled: boolean | number; last_checked_at?: string | null; last_successful_sync_at?: string | null; }
interface Video { video_id: string; title: string; description?: string | null; published_at?: string | null; duration_seconds?: number | null; channel?: string | null; library_path?: string | null; cache_status?: 'NOT_CACHED' | 'PARTIAL' | 'COMPLETE'; cache_last_accessed_at?: string | null; cache_expires_at?: string | null; cache_bytes?: number; cached_fragments?: number; downloaded?: number | boolean; }
interface AdminUser { id: number; username: string; email?: string | null; role: Role; filesystem_slug: string; created_at: string; }
interface CacheEntry { video_id: string; format_key?: string; status: string; last_accessed_at?: string; active_readers?: number; active_writers?: number; }
interface Job { id: string; video_id: string; status: string; priority: number; created_at: string; error?: string | null; }
interface JellyfinStatus {
  enabled: boolean;
  configured: boolean;
  reachable: boolean;
  apiKeyConfigured: boolean;
  statusCode?: number;
  baseUrl?: string;
  serverName?: string;
  version?: string;
  message?: string;
  refreshCount?: number;
  lastRefreshAt?: string;
  lastRefreshSuccess?: boolean;
  lastRefreshMessage?: string;
  runtimeSuccessCount?: number;
  runtimeFailureCount?: number;
  pendingRuntimeSyncs?: number;
  lastRuntimeSyncAt?: string;
  lastRuntimeSyncSuccess?: boolean;
  lastRuntimeSyncMessage?: string;
}

@Component({
  selector: 'app-root',
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly me = signal<Profile | null>(null);
  readonly subscriptions = signal<Subscription[]>([]);
  readonly videos = signal<Video[]>([]);
  readonly settings = signal<Record<string, string>>({});
  readonly adminUsers = signal<AdminUser[]>([]);
  readonly cacheEntries = signal<CacheEntry[]>([]);
  readonly jobs = signal<Job[]>([]);
  readonly jellyfinStatus = signal<JellyfinStatus | null>(null);
  readonly section = signal<Section>('subscriptions');
  readonly authMode = signal<'login' | 'register'>('login');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly notice = signal('');
  readonly error = signal('');

  username = '';
  email = '';
  password = '';
  passwordConfirmation = '';
  oldPassword = '';
  newPassword = '';
  newPasswordConfirmation = '';
  channel = '';

  readonly secretSettings = new Set(['youtube_api_key', 'jellyfin_api_key', 'proxy_password', 'cookie_file', 'youtube_po_token', 'youtube_po_token_provider_args']);
  readonly settingGroups: Array<{ title: string; keys: string[] }> = [
    { title: 'Playback', keys: ['stream_quality', 'allowed_video_codecs', 'preferred_video_codecs', 'allowed_audio_codecs', 'preferred_audio_codecs', 'public_base_url'] },
    { title: 'Cache', keys: ['cache_retention_days', 'cache_min_free_gb', 'background_download_max_mbps', 'newest_videos_to_download'] },
    { title: 'YouTube', keys: ['youtube_api_key', 'initial_channel_import_count', 'subscription_sync_minutes', 'youtube_player_client', 'youtube_po_token_provider_enabled', 'youtube_po_token', 'youtube_po_token_provider_args'] },
    { title: 'Jellyfin', keys: ['jellyfin_enabled', 'jellyfin_url', 'jellyfin_api_key', 'jellyfin_auto_refresh', 'jellyfin_runtime_sync', 'jellyfin_request_timeout_seconds'] },
    { title: 'Network and yt-dlp', keys: ['yt_dlp_path', 'ffmpeg_path', 'proxy_url', 'proxy_username', 'proxy_password', 'cookie_file'] }
  ];

  ngOnInit(): void { this.load(); }

  auth(): void {
    this.clearMessages();
    if (!this.username.trim() || !this.password) { this.error.set('Username and password are required.'); return; }
    if (this.authMode() === 'register' && this.password !== this.passwordConfirmation) { this.error.set('Passwords do not match.'); return; }
    if (this.authMode() === 'register' && this.password.length < 12) { this.error.set('Use a password with at least 12 characters.'); return; }
    this.loading.set(true);
    const url = this.authMode() === 'login' ? '/api/auth/login' : '/api/auth/register';
    this.http.post(url, { username: this.username.trim(), password: this.password, email: this.email.trim() || null }).subscribe({
      next: () => { this.password = ''; this.passwordConfirmation = ''; this.load(); },
      error: (e: unknown) => { this.loading.set(false); this.error.set(this.messageFor(e, 'Unable to authenticate.')); }
    });
  }

  load(): void {
    this.http.get<Profile>('/api/me').subscribe({
      next: profile => { this.me.set(profile); this.loading.set(false); this.clearMessages(); this.loadUserData(); if (profile.role === 'ADMIN') this.loadAdminData(); },
      error: () => { this.me.set(null); this.loading.set(false); }
    });
  }

  private loadUserData(): void {
    this.http.get<Subscription[]>('/api/subscriptions').subscribe({ next: rows => this.subscriptions.set(rows), error: e => this.error.set(this.messageFor(e, 'Could not load subscriptions.')) });
    this.http.get<Video[]>('/api/videos').subscribe({ next: rows => this.videos.set(rows), error: e => this.error.set(this.messageFor(e, 'Could not load videos.')) });
  }

  private loadAdminData(): void {
    this.http.get<Record<string, string>>('/api/admin/settings').subscribe({ next: value => this.settings.set(value), error: e => this.error.set(this.messageFor(e, 'Could not load settings.')) });
    this.http.get<AdminUser[]>('/api/admin/users').subscribe({ next: value => this.adminUsers.set(value), error: e => this.error.set(this.messageFor(e, 'Could not load users.')) });
    this.http.get<CacheEntry[]>('/api/admin/cache').subscribe({ next: value => this.cacheEntries.set(value), error: e => this.error.set(this.messageFor(e, 'Could not load cache status.')) });
    this.http.get<Job[]>('/api/admin/jobs').subscribe({ next: value => this.jobs.set(value), error: e => this.error.set(this.messageFor(e, 'Could not load jobs.')) });
    this.loadJellyfinStatus();
  }

  setSection(value: Section): void {
    this.section.set(value);
    if (value === 'users' || value === 'settings' || value === 'jellyfin' || value === 'cache' || value === 'jobs') this.loadAdminData();
  }

  private loadJellyfinStatus(): void {
    if (this.me()?.role !== 'ADMIN') return;
    this.http.get<JellyfinStatus>('/api/admin/jellyfin/status').subscribe({
      next: value => this.jellyfinStatus.set(value),
      error: e => this.error.set(this.messageFor(e, 'Could not load Jellyfin status.'))
    });
  }

  validateJellyfin(): void {
    this.saving.set(true);
    this.http.post<JellyfinStatus>('/api/admin/jellyfin/validate', {}).subscribe({
      next: value => { this.jellyfinStatus.set(value); this.saving.set(false); this.notice.set(value.reachable ? 'Jellyfin connection validated.' : `Jellyfin validation failed: ${value.message || 'server unreachable'}`); },
      error: e => { this.saving.set(false); this.error.set(this.messageFor(e, 'Could not validate Jellyfin connection.')); }
    });
  }

  refreshJellyfin(): void {
    this.saving.set(true);
    this.http.post<{ success?: boolean; message?: string }>('/api/admin/jellyfin/refresh', {}).subscribe({
      next: result => { this.saving.set(false); this.notice.set(result.success ? 'Jellyfin library refresh requested.' : `Jellyfin refresh failed: ${result.message || 'unknown error'}`); this.loadJellyfinStatus(); },
      error: e => { this.saving.set(false); this.error.set(this.messageFor(e, 'Could not request Jellyfin library refresh.')); }
    });
  }

  addSubscription(): void {
    const value = this.channel.trim();
    if (!value) { this.error.set('Enter a YouTube channel URL, channel ID, or @handle.'); return; }
    this.loading.set(true);
    this.http.post('/api/subscriptions', { channel: value }).subscribe({
      next: () => { this.channel = ''; this.notice.set('Channel added. Refresh it to discover videos.'); this.loading.set(false); this.loadUserData(); },
      error: (e: unknown) => { this.loading.set(false); this.error.set(this.messageFor(e, 'Could not add subscription.')); }
    });
  }

  toggleSubscription(subscription: Subscription): void {
    const enabled = !this.enabled(subscription);
    this.http.patch(`/api/subscriptions/${subscription.id}`, { enabled }).subscribe({
      next: () => { this.notice.set(enabled ? 'Subscription enabled.' : 'Subscription paused.'); this.loadUserData(); },
      error: (e: unknown) => this.error.set(this.messageFor(e, 'Could not update subscription.'))
    });
  }

  refreshSubscription(subscription: Subscription): void {
    this.loading.set(true);
    this.http.post<{ discovered?: number }>(`/api/subscriptions/${subscription.id}/refresh`, {}).subscribe({
      next: result => { this.loading.set(false); this.notice.set(`Refresh complete${result.discovered === undefined ? '.' : `: ${result.discovered} video(s) discovered.`}`); this.loadUserData(); },
      error: (e: unknown) => { this.loading.set(false); this.error.set(this.messageFor(e, 'Could not refresh subscription.')); }
    });
  }

  removeSubscription(subscription: Subscription): void {
    if (!window.confirm(`Remove ${subscription.name} from your subscriptions?`)) return;
    this.http.delete(`/api/subscriptions/${subscription.id}`).subscribe({
      next: () => { this.notice.set('Subscription removed. Shared cache is retained for other users.'); this.loadUserData(); },
      error: (e: unknown) => this.error.set(this.messageFor(e, 'Could not remove subscription.'))
    });
  }

  changePassword(): void {
    this.clearMessages();
    if (!this.oldPassword || !this.newPassword) { this.error.set('Enter your current and new password.'); return; }
    if (this.newPassword.length < 12) { this.error.set('Use a new password with at least 12 characters.'); return; }
    if (this.newPassword !== this.newPasswordConfirmation) { this.error.set('New passwords do not match.'); return; }
    this.saving.set(true);
    this.http.post('/api/me/change-password', { oldPassword: this.oldPassword, newPassword: this.newPassword }).subscribe({
      next: () => { this.saving.set(false); this.oldPassword = ''; this.newPassword = ''; this.newPasswordConfirmation = ''; this.notice.set('Password changed.'); },
      error: (e: unknown) => { this.saving.set(false); this.error.set(this.messageFor(e, 'Could not change password.')); }
    });
  }

  setSetting(key: string, value: string): void { this.settings.update(current => ({ ...current, [key]: value })); }
  settingValue(key: string): string { return this.settings()[key] ?? ''; }

  saveSettings(): void {
    this.saving.set(true);
    this.http.patch('/api/admin/settings', this.settings()).subscribe({
      next: () => { this.saving.set(false); this.notice.set('Global settings saved. Secret values remain masked.'); this.loadAdminData(); },
      error: (e: unknown) => { this.saving.set(false); this.error.set(this.messageFor(e, 'Could not save settings.')); }
    });
  }

  updateRole(user: AdminUser, role: Role): void {
    if (user.id === this.me()?.id && role !== 'ADMIN') { this.error.set('You cannot remove your own administrator access.'); return; }
    this.http.patch(`/api/admin/users/${user.id}`, { role }).subscribe({
      next: () => { this.notice.set(`${user.username} is now ${role}.`); this.loadAdminData(); },
      error: (e: unknown) => this.error.set(this.messageFor(e, 'Could not update user role.'))
    });
  }

  deleteCache(entry: CacheEntry): void {
    if (!window.confirm(`Delete cached media for ${entry.video_id}?`)) return;
    this.http.delete(`/api/admin/cache/${encodeURIComponent(entry.video_id)}`).subscribe({
      next: () => { this.notice.set('Cache entry deleted.'); this.loadAdminData(); },
      error: (e: unknown) => this.error.set(this.messageFor(e, 'Could not delete cache entry.'))
    });
  }

  cancelJob(job: Job): void {
    this.http.post(`/api/admin/jobs/${encodeURIComponent(job.id)}/cancel`, {}).subscribe({
      next: () => { this.notice.set('Job cancelled.'); this.loadAdminData(); },
      error: (e: unknown) => this.error.set(this.messageFor(e, 'Could not cancel job.'))
    });
  }

  enabled(subscription: Subscription): boolean { return subscription.enabled === true || subscription.enabled === 1; }
  downloaded(video: Video): boolean { return video.downloaded === true || video.downloaded === 1 || video.cache_status === 'COMPLETE'; }
  formatBytes(bytes: number | null | undefined): string { const value = Number(bytes || 0); if (value < 1) return '—'; const units = ['B', 'KB', 'MB', 'GB', 'TB']; const index = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024))); return `${(value / Math.pow(1024, index)).toFixed(index === 0 ? 0 : 1)} ${units[index]}`; }
  formatDuration(seconds: number | null | undefined): string { if (!seconds || seconds < 1) return 'Runtime pending'; const hours = Math.floor(seconds / 3600); const minutes = Math.floor((seconds % 3600) / 60); const remainder = seconds % 60; return hours > 0 ? `${hours}:${this.twoDigits(minutes)}:${this.twoDigits(remainder)}` : `${minutes}:${this.twoDigits(remainder)}`; }
  playbackUrl(video: Video): string { return `/play/${encodeURIComponent(video.video_id)}`; }

  logout(): void {
    this.http.post('/api/auth/logout', {}).subscribe({
      next: () => { this.me.set(null); this.subscriptions.set([]); this.videos.set([]); this.settings.set({}); this.setSection('subscriptions'); this.notice.set('Signed out.'); },
      error: () => { this.me.set(null); this.setSection('subscriptions'); }
    });
  }

  clearMessages(): void { this.error.set(''); this.notice.set(''); }
  private twoDigits(value: number): string { return value.toString().padStart(2, '0'); }
  private messageFor(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) { const body = error.error as { message?: string } | string | null; if (typeof body === 'string' && body.trim()) return body; if (body && typeof body === 'object' && typeof body.message === 'string' && body.message.trim()) return body.message; }
    return fallback;
  }
}

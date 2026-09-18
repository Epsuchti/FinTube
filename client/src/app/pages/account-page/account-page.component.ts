import {ChangeDetectionStrategy, Component, OnInit, inject, signal} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {AccountService, Profile} from '../../api';

@Component({
    selector: 'ft-account-page',
    standalone: true,
    imports: [CommonModule, FormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './account-page.component.html'
})
export class AccountPageComponent implements OnInit {
    private readonly accountApi = inject(AccountService);

    readonly profile = signal<Profile | null>(null);
    readonly loading = signal(false);
    readonly saving = signal(false);
    readonly error = signal('');
    readonly notice = signal('');
    readonly youtubeApiKeyConfigured = signal(false);
    oldPassword = '';
    newPassword = '';
    confirmation = '';
    youtubeApiKey = '';

    ngOnInit(): void {
        this.loadProfile();
        this.loadYouTubeApiKeyStatus();
    }

    saveYouTubeApiKey(): void {
        this.error.set('');
        this.notice.set('');
        if (!this.youtubeApiKey.trim()) {
            this.error.set('Enter a YouTube Data API key.');
            return;
        }
        this.saving.set(true);
        this.accountApi.updateYouTubeApiKey({
            updateYouTubeApiKeyRequest: {api_key: this.youtubeApiKey}
        }).subscribe({
            next: status => {
                this.saving.set(false);
                this.youtubeApiKey = '';
                this.youtubeApiKeyConfigured.set(status.configured);
                this.notice.set('YouTube Data API key saved.');
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not save your YouTube API key.'));
            }
        });
    }

    removeYouTubeApiKey(): void {
        this.error.set('');
        this.notice.set('');
        this.saving.set(true);
        this.accountApi.updateYouTubeApiKey({
            updateYouTubeApiKeyRequest: {api_key: ''}
        }).subscribe({
            next: () => {
                this.saving.set(false);
                this.youtubeApiKeyConfigured.set(false);
                this.notice.set('YouTube Data API key removed.');
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not remove your YouTube API key.'));
            }
        });
    }

    changePassword(): void {
        this.error.set('');
        this.notice.set('');
        if (!this.oldPassword || !this.newPassword) {
            this.error.set('Enter your current and new password.');
            return;
        }
        if (this.newPassword.length < 6) {
            this.error.set('Use a new password with at least 6 characters.');
            return;
        }
        if (this.newPassword !== this.confirmation) {
            this.error.set('New passwords do not match.');
            return;
        }
        this.saving.set(true);
        this.accountApi.changePassword({
            changePasswordRequest: {
                oldPassword: this.oldPassword,
                newPassword: this.newPassword
            }
        }).subscribe({
            next: () => {
                this.saving.set(false);
                this.oldPassword = '';
                this.newPassword = '';
                this.confirmation = '';
                this.notice.set('Password changed.');
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not change password.'));
            }
        });
    }

    private loadProfile(): void {
        this.loading.set(true);
        this.accountApi.getCurrentUser().subscribe({
            next: profile => {
                this.profile.set(profile);
                this.loading.set(false);
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load your profile.'));
            }
        });
    }

    private loadYouTubeApiKeyStatus(): void {
        this.accountApi.getYouTubeApiKeyStatus().subscribe({
            next: status => this.youtubeApiKeyConfigured.set(status.configured),
            error: (error: unknown) => this.error.set(this.messageFor(error, 'Could not load your YouTube API key status.'))
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

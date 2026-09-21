import {ChangeDetectionStrategy, Component, EventEmitter, Output, inject, signal} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {AuthenticationService} from '../../api';

@Component({
    selector: 'ft-auth-page',
    standalone: true,
    imports: [CommonModule, FormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './auth-page.component.html'
})
export class AuthPageComponent {
    private readonly authenticationApi = inject(AuthenticationService);

    readonly mode = signal<'login' | 'register' | 'reset-request' | 'reset-confirm'>('login');
    @Output() authenticated = new EventEmitter<void>();
    readonly loading = signal(false);
    readonly error = signal('');
    readonly notice = signal('');
    username = '';
    email = '';
    password = '';
    confirmation = '';
    resetCode = '';

    submit(): void {
        if (this.mode() === 'login') {
            this.login();
        } else {
            this.register();
        }
    }

    login(): void {
        this.error.set('');
        if (!this.username.trim() || !this.password) {
            this.error.set('Username and password are required.');
            return;
        }
        this.loading.set(true);
        this.authenticationApi.login({
            loginRequest: {
                username: this.username.trim(),
                password: this.password
            }
        }).subscribe({
            next: () => this.authenticatedSuccessfully(),
            error: (error: unknown) => this.authenticationFailed(error, 'Unable to authenticate.')
        });
    }

    register(): void {
        this.error.set('');
        if (!this.username.trim() || !this.password) {
            this.error.set('Username and password are required.');
            return;
        }
        if (this.password !== this.confirmation) {
            this.error.set('Passwords do not match.');
            return;
        }
        if (this.password.length < 6) {
            this.error.set('Use a password with at least 6 characters.');
            return;
        }
        this.loading.set(true);
        const email = this.email.trim();
        this.authenticationApi.register({
            registerRequest: {
                username: this.username.trim(),
                ...(email ? {email} : {}),
                password: this.password
            }
        }).subscribe({
            next: () => this.authenticatedSuccessfully(),
            error: (error: unknown) => this.authenticationFailed(error, 'Unable to authenticate.')
        });
    }

    toggle(): void {
        this.mode.update(mode => mode === 'login' ? 'register' : 'login');
        this.password = '';
        this.confirmation = '';
        this.error.set('');
        this.notice.set('');
    }

    showPasswordReset(): void {
        this.mode.set('reset-request'); this.password = ''; this.confirmation = ''; this.error.set(''); this.notice.set('');
    }

    requestPasswordReset(): void {
        this.error.set('');
        if (!this.username.trim()) { this.error.set('Enter your username.'); return; }
        this.loading.set(true);
        this.authenticationApi.requestPasswordReset({passwordResetRequest: {username: this.username.trim()}}).subscribe({
            next: () => { this.loading.set(false); this.mode.set('reset-confirm'); this.notice.set('If that account exists, a reset code was printed to the server console.'); },
            error: (error: unknown) => this.authenticationFailed(error, 'Could not request a reset code.')
        });
    }

    confirmPasswordReset(): void {
        this.error.set('');
        if (!this.resetCode || !this.password) { this.error.set('Enter the reset code and new password.'); return; }
        if (this.password.length < 6) { this.error.set('Use a password with at least 6 characters.'); return; }
        if (this.password !== this.confirmation) { this.error.set('Passwords do not match.'); return; }
        this.loading.set(true);
        this.authenticationApi.confirmPasswordReset({passwordResetConfirmRequest: {username: this.username.trim(), code: this.resetCode, newPassword: this.password}}).subscribe({
            next: () => { this.loading.set(false); this.mode.set('login'); this.password = ''; this.confirmation = ''; this.resetCode = ''; this.notice.set('Password reset. Please sign in with your new password.'); },
            error: (error: unknown) => this.authenticationFailed(error, 'Could not reset password.')
        });
    }

    backToLogin(): void {
        this.mode.set('login'); this.password = ''; this.confirmation = ''; this.resetCode = ''; this.error.set(''); this.notice.set('');
    }

    private authenticatedSuccessfully(): void {
        this.loading.set(false);
        this.password = '';
        this.confirmation = '';
        this.authenticated.emit();
    }

    private authenticationFailed(error: unknown, fallback: string): void {
        this.loading.set(false);
        this.error.set(this.messageFor(error, fallback));
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

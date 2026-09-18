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

    readonly mode = signal<'login' | 'register'>('login');
    @Output() authenticated = new EventEmitter<void>();
    readonly loading = signal(false);
    readonly error = signal('');
    username = '';
    email = '';
    password = '';
    confirmation = '';

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

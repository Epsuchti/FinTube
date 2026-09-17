import {ChangeDetectionStrategy, Component, EventEmitter, Output, inject, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {HttpErrorResponse} from '@angular/common/http';
import {SetupService} from '../../api';

@Component({
    selector: 'ft-initial-setup-page',
    standalone: true,
    imports: [FormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './initial-setup-page.component.html'
})
export class InitialSetupPageComponent {
    private readonly setupApi = inject(SetupService);

    @Output() created = new EventEmitter<void>();
    readonly loading = signal(false);
    readonly error = signal('');
    token = '';
    username = '';
    email = '';
    password = '';
    confirmation = '';

    createInitialAdmin(): void {
        this.error.set('');
        if (this.password !== this.confirmation) {
            this.error.set('Passwords do not match.');
            return;
        }
        if (this.password.length < 12 || !this.token.trim()) {
            this.error.set('A setup token and a password of at least 12 characters are required.');
            return;
        }
        this.loading.set(true);
        const email = this.email.trim();
        this.setupApi.createInitialAdmin({
            createAdminRequest: {
                username: this.username.trim(),
                ...(email ? {email} : {}),
                password: this.password,
                token: this.token.trim()
            }
        }).subscribe({
            next: () => {
                this.loading.set(false);
                this.token = '';
                this.password = '';
                this.confirmation = '';
                this.created.emit();
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not create the administrator.'));
            }
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

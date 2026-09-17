import {ChangeDetectionStrategy, Component, OnInit, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {AdminService, AccountService, AdminUser, Role} from '../../api';

@Component({
    selector: 'ft-admin-users-page',
    standalone: true,
    imports: [CommonModule, DatePipe, FormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './admin-users-page.component.html'
})
export class AdminUsersPageComponent implements OnInit {
    private readonly adminApi = inject(AdminService);
    private readonly accountApi = inject(AccountService);

    readonly users = signal<AdminUser[]>([]);
    readonly currentUserId = signal<number | undefined>(undefined);
    readonly loading = signal(false);
    readonly saving = signal(false);
    readonly error = signal('');
    readonly notice = signal('');

    ngOnInit(): void {
        this.loadUsers();
        this.loadCurrentUser();
    }

    updateRole(user: AdminUser, role: Role): void {
        if (user.id === this.currentUserId() && role !== Role.Admin) {
            this.error.set('You cannot remove your own administrator access.');
            return;
        }
        this.saving.set(true);
        this.adminApi.updateUserRole({
            id: user.id,
            updateRoleRequest: {role}
        }).subscribe({
            next: () => {
                this.saving.set(false);
                this.notice.set(`${user.username} is now ${role}.`);
                this.loadUsers();
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not update user role.'));
            }
        });
    }

    private loadUsers(): void {
        this.loading.set(true);
        this.adminApi.listUsers().subscribe({
            next: users => {
                this.users.set(users);
                this.loading.set(false);
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load users.'));
            }
        });
    }

    private loadCurrentUser(): void {
        this.accountApi.getCurrentUser().subscribe({
            next: user => this.currentUserId.set(user.id),
            error: (error: unknown) => this.error.set(this.messageFor(error, 'Could not identify the current user.'))
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

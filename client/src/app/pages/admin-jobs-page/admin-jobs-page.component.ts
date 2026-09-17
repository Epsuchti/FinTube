import {ChangeDetectionStrategy, Component, OnInit, inject, signal} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {AdminService, Job} from '../../api';

@Component({
    selector: 'ft-admin-jobs-page',
    standalone: true,
    imports: [CommonModule, DatePipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './admin-jobs-page.component.html'
})
export class AdminJobsPageComponent implements OnInit {
    private readonly adminApi = inject(AdminService);

    readonly jobs = signal<Job[]>([]);
    readonly loading = signal(false);
    readonly saving = signal(false);
    readonly error = signal('');
    readonly notice = signal('');

    ngOnInit(): void {
        this.loadJobs();
    }

    cancel(job: Job): void {
        this.saving.set(true);
        this.adminApi.cancelJob({id: job.id}).subscribe({
            next: () => {
                this.saving.set(false);
                this.notice.set('Job cancelled.');
                this.loadJobs();
            },
            error: (error: unknown) => {
                this.saving.set(false);
                this.error.set(this.messageFor(error, 'Could not cancel job.'));
            }
        });
    }

    private loadJobs(): void {
        this.loading.set(true);
        this.adminApi.listJobs().subscribe({
            next: jobs => {
                this.jobs.set(jobs);
                this.loading.set(false);
            },
            error: (error: unknown) => {
                this.loading.set(false);
                this.error.set(this.messageFor(error, 'Could not load jobs.'));
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

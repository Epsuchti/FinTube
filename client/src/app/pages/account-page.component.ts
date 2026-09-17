import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

export interface AccountPageProfile { username: string; role: string; filesystemSlug?: string; }
@Component({selector: 'ft-account-page', standalone: true, imports: [CommonModule, FormsModule], changeDetection: ChangeDetectionStrategy.OnPush, templateUrl: './account-page.component.html'})
export class AccountPageComponent {
  @Input() profile: AccountPageProfile | null = null;
  @Input() saving = false;
  @Output() changePassword = new EventEmitter<{oldPassword: string; newPassword: string; confirmation: string}>();
  oldPassword = ''; newPassword = ''; confirmation = '';
  submit(): void { this.changePassword.emit({oldPassword: this.oldPassword, newPassword: this.newPassword, confirmation: this.confirmation}); this.oldPassword = ''; this.newPassword = ''; this.confirmation = ''; }
}

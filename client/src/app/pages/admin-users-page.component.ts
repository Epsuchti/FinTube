import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule, DatePipe} from '@angular/common'; import {FormsModule} from '@angular/forms';
import {Role} from '../api';
export interface AdminUserPageItem { id:number; username:string; email?:string|null; role:Role; filesystem_slug:string; created_at:string; }
@Component({selector:'ft-admin-users-page',standalone:true,imports:[CommonModule,DatePipe,FormsModule],changeDetection:ChangeDetectionStrategy.OnPush,templateUrl:'./admin-users-page.component.html'})
export class AdminUsersPageComponent { @Input({required:true}) users:AdminUserPageItem[]=[]; @Input() currentUserId?:number; @Output() roleChange=new EventEmitter<{user:AdminUserPageItem; role:Role}>(); }

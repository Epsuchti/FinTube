import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core'; import {CommonModule, DatePipe} from '@angular/common';
export interface JobPageItem {id:string;video_id:string;status:string;priority:number;created_at:string;error?:string|null;}
@Component({selector:'ft-admin-jobs-page',standalone:true,imports:[CommonModule,DatePipe],changeDetection:ChangeDetectionStrategy.OnPush,templateUrl:'./admin-jobs-page.component.html'}) export class AdminJobsPageComponent { @Input({required:true}) jobs:JobPageItem[]=[]; @Output() cancel=new EventEmitter<JobPageItem>(); }

import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core'; import {CommonModule} from '@angular/common';
export interface SettingGroup { title:string; keys:string[]; }
@Component({selector:'ft-admin-settings-page',standalone:true,imports:[CommonModule],changeDetection:ChangeDetectionStrategy.OnPush,templateUrl:'./admin-settings-page.component.html'})
export class AdminSettingsPageComponent { @Input({required:true}) groups:SettingGroup[]=[]; @Input({required:true}) values:Record<string,string>={}; @Input({required:true}) secretSettings=new Set<string>(); @Input() saving=false; @Output() valueChange=new EventEmitter<{key:string; value:string}>(); @Output() save=new EventEmitter<void>(); value(key:string):string{return this.values[key] ?? '';} }

import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

/** API transport boundary; generated OpenAPI clients can replace this service without touching views. */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  get<T>(url: string) { return this.http.get<T>(url); }
  post<T>(url: string, body: unknown) { return this.http.post<T>(url, body); }
  patch<T>(url: string, body: unknown) { return this.http.patch<T>(url, body); }
  delete<T>(url: string) { return this.http.delete<T>(url); }
}

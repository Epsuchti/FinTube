import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { AsyncPipe } from '@angular/common';
import { catchError, of } from 'rxjs';

interface HelloResponse { message: string; }

@Component({
  selector: 'app-root',
  imports: [AsyncPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  private readonly http = inject(HttpClient);
  readonly hello$ = this.http.get<HelloResponse>('/api/hello').pipe(
    catchError(() => of({ message: 'The FinTube API is not running yet.' }))
  );
}

import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Dashboard } from './dashboard/dashboard';

/** The shell. One page, so it frames the dashboard and does nothing else. */
@Component({
  selector: 'app-root',
  imports: [Dashboard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {}

import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { Subscription, switchMap, takeWhile, timer } from 'rxjs';
import { InventoryApi, InventoryApiError } from '../core/inventory-api';
import type { ImportResult, ImportStatus } from '../core/models';

/** How often the run is polled. Fast enough to feel live, slow enough not to hammer the API. */
const POLL_INTERVAL_MS = 1000;

const ACCEPTED_EXTENSIONS = ['.csv', '.xls', '.xlsx'];

/** Where the dialog is in the upload: which of its four faces it is showing. */
type Phase = 'choose' | 'uploading' | 'running' | 'done';

/**
 * Import Data, from picking a file to reading the outcome.
 *
 * <p>The import is a job, not a request. The upload returns a run id immediately and the real work
 * happens on the server, so this polls the status endpoint and shows the counters climbing. That
 * is also why the dialog cannot simply close on success: the result -- how many rows went in, how
 * many were rejected and which -- only exists after the run finishes.
 */
@Component({
  selector: 'app-import-dialog',
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './import-dialog.html',
  styleUrl: './import-dialog.css',
})
export class ImportDialog {
  private readonly api = inject(InventoryApi);
  private readonly destroyRef = inject(DestroyRef);
  private polling?: Subscription;

  /**
   * The finished run, handed to the dashboard so it can reload the table and keep showing the
   * outcome in the summary panel once this dialog is gone.
   */
  readonly finished = output<ImportResult>();
  readonly closed = output<void>();

  protected readonly phase = signal<Phase>('choose');
  protected readonly file = signal<File | null>(null);
  protected readonly result = signal<ImportResult | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly dragging = signal(false);

  protected readonly accept = ACCEPTED_EXTENSIONS.join(',');

  /** True while the file is in the server's hands and there is nothing for the user to do. */
  protected readonly busy = computed(() => this.phase() === 'uploading' || this.phase() === 'running');

  protected readonly status = computed<ImportStatus | null>(() => this.result()?.status ?? null);

  /**
   * A run that ended in FAILED, or one that completed without importing a single row: both are
   * worth colouring as a problem rather than as a success with a zero in it.
   */
  protected readonly failed = computed(() => {
    const result = this.result();
    return result?.status === 'FAILED' || (this.phase() === 'done' && result?.summary.importedCount === 0);
  });

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.chooseFile(input.files?.[0] ?? null);
    // Cleared so that picking the same file twice in a row still raises a change event.
    input.value = '';
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.busy()) {
      this.dragging.set(true);
    }
  }

  protected onDragLeave(): void {
    this.dragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    if (!this.busy()) {
      this.chooseFile(event.dataTransfer?.files?.[0] ?? null);
    }
  }

  /**
   * The extension is checked here only to save a doomed round trip and give an instant answer.
   * The backend re-checks it, and also checks the file's leading bytes, which is the check that
   * actually decides -- a client-side test on a filename proves nothing.
   */
  protected chooseFile(file: File | null): void {
    if (!file) {
      return;
    }
    const name = file.name.toLowerCase();
    if (!ACCEPTED_EXTENSIONS.some((extension) => name.endsWith(extension))) {
      this.file.set(null);
      this.error.set(`'${file.name}' is not a .csv, .xls or .xlsx file.`);
      return;
    }
    this.error.set(null);
    this.result.set(null);
    this.file.set(file);
    this.phase.set('choose');
  }

  protected upload(): void {
    const file = this.file();
    if (!file || this.busy()) {
      return;
    }
    this.phase.set('uploading');
    this.error.set(null);
    this.api.startImport(file).subscribe({
      next: (accepted) => {
        this.result.set(accepted);
        this.phase.set('running');
        this.poll(accepted.importId);
      },
      error: (error: InventoryApiError) => {
        this.error.set(error.message);
        this.phase.set('choose');
      },
    });
  }

  /** Back to an empty dialog, ready for another file. */
  protected reset(): void {
    this.polling?.unsubscribe();
    this.file.set(null);
    this.result.set(null);
    this.error.set(null);
    this.phase.set('choose');
  }

  protected close(): void {
    if (this.busy()) {
      return;
    }
    this.polling?.unsubscribe();
    this.closed.emit();
  }

  /**
   * Polls until the run reaches a terminal status.
   *
   * <p>`takeWhile` is inclusive so the terminal response is the one that lands in the signals --
   * that response holds the final counts, and dropping it would leave the dialog showing the
   * second-to-last poll forever.
   */
  private poll(importId: number): void {
    this.polling?.unsubscribe();
    this.polling = timer(0, POLL_INTERVAL_MS)
      .pipe(
        switchMap(() => this.api.importStatus(importId)),
        takeWhile((result) => result.status === 'PENDING' || result.status === 'RUNNING', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (result) => {
          this.result.set(result);
          if (result.status === 'COMPLETED' || result.status === 'FAILED') {
            this.phase.set('done');
            this.finished.emit(result);
          }
        },
        error: (error: InventoryApiError) => {
          this.error.set(error.message);
          this.phase.set('done');
        },
      });
  }
}

import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { ImportDialog } from '../import-dialog/import-dialog';
import { PAGE_SIZES, ProductsStore } from '../core/products-store';
import { ProductsTable } from '../products-table/products-table';
import { SummaryPanel } from '../summary-panel/summary-panel';
import type { ImportResult, SortColumn } from '../core/models';

/**
 * The dashboard page: the table on the left, the summary panel on the right, and the way in for a
 * new file.
 *
 * <p>It owns the {@link ProductsStore} rather than sharing a root one, so the table's position and
 * sort belong to the page being looked at and are not left behind when it is navigated away from.
 * It also holds the last import, which the dialog produces and the summary panel displays.
 */
@Component({
  selector: 'app-dashboard',
  imports: [ImportDialog, ProductsTable, SummaryPanel],
  providers: [ProductsStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css',
})
export class Dashboard implements OnInit {
  protected readonly store = inject(ProductsStore);
  protected readonly pageSizes = PAGE_SIZES;
  protected readonly importOpen = signal(false);

  /** The last run of this visit, so the panel keeps showing it after the dialog is closed. */
  protected readonly lastImport = signal<ImportResult | null>(null);

  ngOnInit(): void {
    this.store.refresh();
  }

  protected onSort(column: SortColumn): void {
    this.store.sort(column);
  }

  protected onPageSize(event: Event): void {
    this.store.setPageSize(Number((event.target as HTMLSelectElement).value));
  }

  /**
   * Reloads while the dialog is still open, so the numbers behind it are already right when it is
   * dismissed. A failed run is reloaded too: it may have committed some chunks before it stopped.
   */
  protected onImportFinished(result: ImportResult): void {
    this.lastImport.set(result);
    this.store.refresh();
  }
}

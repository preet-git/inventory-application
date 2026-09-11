import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ImportResult, InventorySummary } from '../core/models';

/**
 * The right-hand column: what went in, and what did not.
 *
 * <p>Two sections rather than one, because they answer two different questions. The first is about
 * the inventory as a whole and is true whether or not anything was imported today. The second is
 * about the last run in particular, and is where the rejected rows stay once the import dialog has
 * been dismissed.
 *
 * <p>Presentational: everything it shows arrives as an input, so the page decides what the last
 * run was and this only decides how it looks.
 */
@Component({
  selector: 'app-summary-panel',
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './summary-panel.html',
  styleUrl: './summary-panel.css',
})
export class SummaryPanel {
  readonly summary = input<InventorySummary | null>(null);
  readonly lastImport = input<ImportResult | null>(null);
  readonly loading = input(false);

  /**
   * Inventory value is rendered by hand rather than by `CurrencyPipe`, because the file never says
   * what currency it holds. Grouping separators and two decimals are safe to assume; a symbol is
   * not.
   */
  protected readonly totalProducts = computed(() => this.summary()?.totalProducts ?? 0);
  protected readonly totalValue = computed(() => this.summary()?.totalInventoryValue ?? 0);
  protected readonly averageAge = computed(() => this.summary()?.averageStockAgeDays ?? 0);

  /** A run that stopped on an error, as opposed to one that finished having rejected some rows. */
  protected readonly runFailed = computed(() => this.lastImport()?.status === 'FAILED');
}

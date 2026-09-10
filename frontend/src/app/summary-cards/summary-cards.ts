import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { InventorySummary } from '../core/models';

/**
 * The three figures the brief asks for, over the whole inventory.
 *
 * <p>Purely presentational: it is handed a summary and renders it, so the same numbers can be
 * driven by any owner without this knowing where they came from.
 */
@Component({
  selector: 'app-summary-cards',
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './summary-cards.html',
  styleUrl: './summary-cards.css',
})
export class SummaryCards {
  readonly summary = input<InventorySummary | null>(null);
  readonly loading = input(false);

  /**
   * Inventory value is rendered by hand rather than by `CurrencyPipe`, because the file never says
   * what currency it holds. Grouping separators and two decimals are safe to assume; a symbol is
   * not.
   */
  readonly totalValue = computed(() => this.summary()?.totalInventoryValue ?? 0);
  readonly totalProducts = computed(() => this.summary()?.totalProducts ?? 0);
  readonly averageAge = computed(() => this.summary()?.averageStockAgeDays ?? 0);
}

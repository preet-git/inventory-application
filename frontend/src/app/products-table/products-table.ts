import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { IsoDatePipe } from '../core/iso-date.pipe';
import type { ProductRow, SortColumn, SortDirection } from '../core/models';

/** A column heading: the key the API sorts by, the label, and how the cells are aligned. */
interface ColumnDefinition {
  key: SortColumn;
  label: string;
  numeric: boolean;
}

const COLUMNS: readonly ColumnDefinition[] = [
  { key: 'rowNumber', label: 'Row', numeric: true },
  { key: 'productSku', label: 'Product SKU', numeric: false },
  { key: 'productName', label: 'Product Name', numeric: false },
  { key: 'category', label: 'Category', numeric: false },
  { key: 'purchaseDate', label: 'Purchase Date', numeric: false },
  { key: 'unitPrice', label: 'Unit Price', numeric: true },
  { key: 'quantity', label: 'Quantity', numeric: true },
  { key: 'lineValue', label: 'Line Value', numeric: true },
  { key: 'stockAgeDays', label: 'Stock Age (Days)', numeric: true },
];

/**
 * The table itself. Sorting is server-side -- every column the backend supports is offered, and a
 * click reports the column rather than reordering anything locally, because the page on screen is
 * one page of many and sorting it in the browser would sort only what is visible.
 */
@Component({
  selector: 'app-products-table',
  imports: [DecimalPipe, IsoDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './products-table.html',
  styleUrl: './products-table.css',
})
export class ProductsTable {
  readonly rows = input.required<ProductRow[]>();
  readonly sortBy = input.required<SortColumn>();
  readonly direction = input.required<SortDirection>();
  readonly loading = input(false);

  readonly sortChange = output<SortColumn>();

  protected readonly columns = COLUMNS;

  /** What a screen reader announces for the header, and what the arrow in it reflects. */
  protected ariaSort(column: SortColumn): 'ascending' | 'descending' | 'none' {
    if (this.sortBy() !== column) {
      return 'none';
    }
    return this.direction() === 'ASC' ? 'ascending' : 'descending';
  }

  /**
   * Stock age is negative when a purchase date is in the future. The backend reports that rather
   * than clamping it to zero, and so does the table -- a future date is a data problem worth
   * seeing, not one worth hiding.
   */
  protected isFutureDated(row: ProductRow): boolean {
    return row.stockAgeDays < 0;
  }
}

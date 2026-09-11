import { computed, inject, Injectable, signal } from '@angular/core';
import { InventoryApi, InventoryApiError } from './inventory-api';
import type { InventorySummary, ProductRow, SortColumn, SortDirection } from './models';

/** Page sizes the table offers. The backend caps a page at 200, so none of these exceeds it. */
export const PAGE_SIZES = [10, 20, 50, 100] as const;

/**
 * The dashboard's table state: what is sorted how, which page is showing, and how to move.
 *
 * <p>Paging is by cursor because that is what the API offers, and a cursor only says where the
 * *next* page starts. Going back is therefore not a matter of subtracting one from an offset: the
 * cursor that opened each visited page is kept in a stack, and "previous" drops the top and
 * re-fetches with the one beneath. The consequence the UI has to live with is that there is no
 * jumping to page 7 -- pages are reachable only in the order they were walked.
 *
 * <p>Changing the sort invalidates every cursor, so it resets the stack to the first page.
 */
@Injectable()
export class ProductsStore {
  private readonly api = inject(InventoryApi);

  /** The cursor that opened each page visited so far. The first page has none, hence null. */
  private readonly cursors = signal<(string | null)[]>([null]);

  readonly rows = signal<ProductRow[]>([]);
  readonly summary = signal<InventorySummary | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly sortBy = signal<SortColumn>('productSku');
  readonly direction = signal<SortDirection>('ASC');
  readonly pageSize = signal<number>(20);

  private readonly nextCursor = signal<string | null>(null);

  readonly pageIndex = computed(() => this.cursors().length - 1);
  readonly hasPrevious = computed(() => this.pageIndex() > 0);
  readonly hasNext = computed(() => this.nextCursor() !== null);
  readonly isEmpty = computed(() => !this.loading() && this.rows().length === 0);

  /** "1-20 of 1,204", or the honest "0 of 0" before anything is imported. */
  readonly rangeLabel = computed(() => {
    const count = this.rows().length;
    const total = this.summary()?.totalProducts ?? 0;
    if (count === 0) {
      return `0 of ${total.toLocaleString()}`;
    }
    const first = this.pageIndex() * this.pageSize() + 1;
    return `${first.toLocaleString()}-${(first + count - 1).toLocaleString()} of ${total.toLocaleString()}`;
  });

  /** First page plus the summary card. Also the call that reloads both after an import. */
  refresh(): void {
    this.cursors.set([null]);
    this.loadSummary();
    this.load(null);
  }

  /**
   * Sorts by a column, toggling direction when it is already the sorted one.
   *
   * <p>A new column starts ascending, except stock age: the interesting end of it is the oldest
   * stock, so it opens on the largest ages.
   */
  sort(column: SortColumn): void {
    if (this.sortBy() === column) {
      this.direction.set(this.direction() === 'ASC' ? 'DESC' : 'ASC');
    } else {
      this.sortBy.set(column);
      this.direction.set(column === 'stockAgeDays' ? 'DESC' : 'ASC');
    }
    this.cursors.set([null]);
    this.load(null);
  }

  next(): void {
    const cursor = this.nextCursor();
    if (!cursor || this.loading()) {
      return;
    }
    this.cursors.update((stack) => [...stack, cursor]);
    this.load(cursor);
  }

  previous(): void {
    if (!this.hasPrevious() || this.loading()) {
      return;
    }
    const stack = this.cursors().slice(0, -1);
    this.cursors.set(stack);
    this.load(stack[stack.length - 1] ?? null);
  }

  setPageSize(size: number): void {
    if (size === this.pageSize()) {
      return;
    }
    // A cursor is a position in a walk of a given step length, so a new size restarts the walk.
    this.pageSize.set(size);
    this.cursors.set([null]);
    this.load(null);
  }

  loadSummary(): void {
    this.api.summary().subscribe({
      next: (summary) => this.summary.set(summary),
      error: (error: InventoryApiError) => this.error.set(error.message),
    });
  }

  private load(cursor: string | null): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .products({
        cursor,
        size: this.pageSize(),
        sortBy: this.sortBy(),
        direction: this.direction(),
      })
      .subscribe({
        next: (page) => {
          this.rows.set(page.content);
          this.nextCursor.set(page.nextCursor);
          this.loading.set(false);
        },
        error: (error: InventoryApiError) => {
          this.rows.set([]);
          this.nextCursor.set(null);
          this.error.set(error.message);
          this.loading.set(false);
        },
      });
  }
}

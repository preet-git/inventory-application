/**
 * The shapes the backend returns, mirrored one-for-one.
 *
 * <p>Nothing here is reshaped on the way in: the API already computes the two derived columns the
 * dashboard shows (line value and stock age), so the client displays what it is given rather than
 * recomputing it from a date it would have to parse in the browser's timezone.
 */

/** Lifecycle of one upload, straight from the backend's enum. */
export type ImportStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

/** What the import has read and written so far. Counters climb while the run is in flight. */
export interface ImportRunSummary {
  rowsRead: number;
  importedCount: number;
  rejectedCount: number;
  startedAt: string | null;
  finishedAt: string | null;
  failureMessage: string | null;
}

/**
 * Which rows were dropped, by their number in the file.
 *
 * `truncated` says the list is only the first page, and the rest must be fetched from the
 * rejections endpoint.
 */
export interface RejectedRows {
  count: number;
  rowNumbers: number[];
  truncated: boolean;
}

export interface ImportResult {
  importId: number;
  fileName: string;
  status: ImportStatus;
  summary: ImportRunSummary;
  rejectedRows: RejectedRows;
}

/** The summary card, over the whole inventory rather than one import. */
export interface InventorySummary {
  totalProducts: number;
  totalInventoryValue: number;
  averageStockAgeDays: number;
}

/** One table row. `purchaseDate` is an ISO date string, never a `Date`. */
export interface ProductRow {
  rowNumber: number;
  productSku: string;
  productName: string;
  category: string;
  purchaseDate: string;
  unitPrice: number;
  quantity: number;
  lineValue: number;
  stockAgeDays: number;
}

export type SortDirection = 'ASC' | 'DESC';

/**
 * One page of the table.
 *
 * <p>Cursor-paged: `nextCursor` is fed back to fetch the page after this one. There is no total
 * count here by design -- the summary endpoint carries it.
 */
export interface ProductPage {
  content: ProductRow[];
  size: number;
  sortBy: string;
  direction: SortDirection;
  nextCursor: string | null;
  hasMore: boolean;
}

/** Every error body the API returns has these two fields, whatever went wrong. */
export interface ApiError {
  code: string;
  message: string;
}

/** The columns the backend will sort by. Any other value is a 400, so the table offers only these. */
export const SORTABLE_COLUMNS = [
  'rowNumber',
  'productSku',
  'productName',
  'category',
  'purchaseDate',
  'unitPrice',
  'quantity',
  'lineValue',
  'stockAgeDays',
] as const;

export type SortColumn = (typeof SORTABLE_COLUMNS)[number];

#444 — Collections can now be dragged to reorder on Home

## Root cause

Home listed collections in insertion order with no reorder path anywhere: `CollectionRepository` kept one `ArrayList<PubKeyCollection>` whose order IS the persisted payload order (the whole list serializes into the single encrypted payload; no Room rows, no sort column), `addNew()` only appended, `delete()` only spliced, and `CollectionsAdapter.onBindViewHolder` wired exactly one gesture — the row click. The only way to move a collection was delete and re-add.

## The fix

Design C from the issue census (handle as the affordance, long-press as the accelerator), Home only:

- `CollectionRepository.move(fromIndex, toIndex)` — the `delete(index)` shape verbatim: bounds-checked inside `synchronized(pubKeyCollections)` (the #89 monitor every mutator holds), then `sync()`. The ArrayList order being the payload order means a reorder persists by construction; the sync() dedupe (`distinctBy { it.id }`) keeps first-occurrence order, so a reordered list round-trips unchanged.
- `collection_item_row_layout.xml` gains `rvItemCollectionDragHandle` (Material `ic_baseline_drag_handle_24`, `#E4E4E4` tint like the wallet icon). The stacked layout — the choose-collection sheet and the sweep picker — gets nothing: no handle view, no `ItemTouchHelper` attach, and their adapters never call `setDragEnabled`, so the picker stays a picker in Home's order.
- `CollectionsAdapter`: `setDragEnabled(enabled, onStartDrag)` gates the drag start per host; `moveItem(from, to)` submits a reordered copy through the differ (`AsyncListDiffer.currentList` is never mutated in place — the `update()` copy discipline); the handle's touch listener starts the drag, never a tap on the row (the row click wiring is untouched).
- `HomeActivity.setUpCollectionList()`: `ItemTouchHelper` with `makeMovementFlags(UP or DOWN, 0)` — no swipe — attached to `binding.collectionRecyclerView`. `onMove` persists per crossing (`repository.move(from, to)`), so a mid-drag process kill leaves crossed positions saved and the LiveData emission converges the adapter and every observer on the repository's order. `clearView` shows the "Collection moved. Home order saved." snackbar (the floating-snackbar idiom); no undo — re-drag is the undo. Long-press drag stays on (`isLongPressDragEnabled` default).
- `SyncRerunPolicy.signatureOf` becomes order-insensitive (per-collection fragments sorted before joining): a reorder changes no chain data, so it must not arm a network sync round — previously a persisted reorder changed the signature and armed a full round after every drag. Add/remove/replace-on-import still arm (#47 preserved, pinned below).
- `#429` discipline preserved: nothing in the drag path writes `itemView.alpha`; the drag rides ItemTouchHelper's translation, the grey stays on content views, and the `CollectionRowGreyRenderTest` source-shape pins (`assertFalse(src.contains("holder.view.alpha"))`, `dimRowContent` arms, attach/detach symmetry) pass unchanged.

## Verification, in plain words

- `:app:compileStagingDebugKotlin` pass (exit 0) after the fix-ups (initial compile caught the forward reference and the missing `onSwiped` override — both corrected).
- New `CollectionRepositoryMoveTest` (3 legs, Robolectric, real `SentinelCollectionStore` idiom from `CollectionsRestoreAfterImportTest`): drag order persists to the payload AND a fresh repository `read()` — the restart — restores the dragged order; out-of-range/no-op moves never reorder in memory or on disk; composed crossings reduce to the inverse permutation.
- `SyncRerunPolicyTest` gains two #444 legs: a pure reorder does not re-arm (`assertFalse(shouldRerun)`), a reorder plus a genuine mid-round pub add still does (`assertTrue`) — the #47 boundary.
- Full `:app:testStagingDebugUnitTest` suite pass (exit 0, includes the pre-existing `CollectionRowGreyRenderTest`, `CollectionsRestoreAfterImportTest`, Koin-graph tests).
- QA docs updated: `TEST_PLAN_6.0.md` COL-23 now expects the drag UI (was "No reorder UI found in code (confirm)"), P2→P1; Maestro `COL-23.yaml` + `COL-23-restore.yaml` generated from `gen/flows_d.py` (91 flows, 0 YAML errors) — drag by handle with the moved-snackbar assertion, restore drag for cleanup; restart-persistence stays with the unit legs (fixture state is shared across COL runs).

## Device legs — deliberately not re-run here

- Pull-to-refresh after a drag: ItemTouchHelper's `requestDisallowInterceptTouchEvent` claim is library behavior (recyclerview 1.2.1 in-tree); the drag adds no parent-level touch listener.
- Greyed-row drag (#429): the grey rides content views outside every animator's write set and the drag path writes no alpha — structurally identical to the pinned `CollectionRowGreyRenderTest` invariant.
- The Maestro COL-23 flow is the on-device proof path for the visual drag.

## Out of scope (per the issue)

Sort commands (alphabetical/by balance), reordering inside the chooser sheet, any Room migration — none applies to collections.
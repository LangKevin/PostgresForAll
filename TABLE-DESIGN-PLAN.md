# Plan: managing a table's structure (columns, keys, indexes, constraints, triggers)

**Goal:** right-click a table and create, view, change and drop everything that defines
it, like SSMS's table node and designer:
- columns
- primary key and foreign keys
- unique, check and exclusion constraints
- indexes
- triggers

Every change is shown as SQL before it runs and is applied safely.

## Status (2026-10-21)

Implemented: phases 1–3, 5 and 6 (right-click dialogs; no designer tab).
- **Decisions taken:** dialogs only; the SQL preview is always shown; "large table" means 100,000+ rows or 100 MB+ (`LargeTableRows` / `LargeTableBytes` in the config). Row-data editing is out of scope.
- **Tree:** Columns, Keys, Constraints, Indexes, Triggers and Referenced by under each table.
- **Menus:** create / edit / rename / drop for every object kind; validate, reindex, enable/disable trigger; truncate / rename / drop table; view dependencies; full Script as CREATE.
- **Tests:** `DdlBuilderTest`, `TableStructureLiveTest` (throwaway databases), and an E2E step that adds and drops an index through the dialogs.
- **Not done:** the table designer tab (section 2.3).

## 1. Where we are today

| Area | Now |
|---|---|
| Tree under a table | **Columns** only |
| Table right-click | Script as SELECT, Script as CREATE, Refresh |
| Script as CREATE | Columns and primary key only. No indexes, foreign keys, checks, triggers or comments |
| Catalog reads (`SchemaInspector`) | Tables, views, functions, columns, roles |

## 2. What the user gets

### 2.1 Tree: the table expands like SSMS

```
public
└─ Tables
   └─ orders                          TABLE · 12,400 rows · 3.1 MB
      ├─ Columns (8)                  id  bigint  PK · customer_id  bigint  NOT NULL · …
      ├─ Keys (3)                     🔑 orders_pkey (PK) · ↗ orders_customer_id_fkey → customers(id) · …
      ├─ Constraints (2)              orders_total_check  CHECK (total >= 0) · orders_code_key  UNIQUE
      ├─ Indexes (4)                  orders_pkey · ix_orders_customer (btree) · … (size · scans)
      ├─ Triggers (1)                 trg_orders_audit  AFTER INSERT OR UPDATE  (enabled)
      └─ Referenced by (2)            invoices.order_id_fkey · shipments.order_id_fkey
```

Each folder loads lazily, and each object has its own icon and a dimmed detail. Disabled
triggers and constraints that haven't been validated yet (`NOT VALID`) are greyed out.

### 2.2 Right-click menus (each create / change / drop action)

| Node | Menu |
|---|---|
| **Table** | **Design table…** (designer tab, §2.3) · New column / index / foreign key / check / unique / trigger · Rename… · Truncate… · Drop… · Script as CREATE / DROP / SELECT / INSERT / UPDATE / DELETE · View dependencies · Refresh |
| **Columns folder** | New column… |
| **Column** | Edit… (type, nullability, default, identity, comment) · Rename… · Drop… · Script as ALTER |
| **Keys folder** | New primary key… · New foreign key… |
| **Primary key** | Edit columns… (drop + recreate) · Rename… · Drop… |
| **Foreign key** | Edit… (columns, referenced table/columns, ON UPDATE/DELETE, deferrable) · Validate · Rename… · Drop… · Go to referenced table |
| **Constraints folder** | New check… · New unique… · New exclusion… |
| **Check / unique / exclusion** | Edit… · Validate (if `NOT VALID`) · Rename… · Drop… |
| **Indexes folder** | New index… |
| **Index** | Edit… (drop + recreate, optionally concurrently) · Rename… · Reindex (concurrently) · Drop… · Show usage and size |
| **Triggers folder** | New trigger… |
| **Trigger** | Enable / Disable · Edit… · Rename… · Drop… · Open trigger function |
| **Referenced by** | Go to the referencing table · Drop that foreign key… |

All actions open a dialog. All destructive ones go through the existing **type-the-name**
confirmation.

### 2.3 Table designer (a document tab)

**Design table…** opens a tab with sub-tabs: **Columns · Keys · Constraints · Indexes ·
Triggers · SQL**.
- **Editing:** each sub-tab is an editable grid (add, edit, delete rows, with drag to
  reorder columns for new tables).
- **Pending changes:** changes collect in a change set and the SQL tab updates live with
  the generated DDL.
- **Toolbar:** **Apply** · **Discard** · **Open SQL in a query tab** · **Validate**
  (a dry run that executes inside a transaction and rolls back).
- **New table…** on the Tables folder opens the same designer, empty.

### 2.4 Dialogs (one per object type)

| Dialog | Fields |
|---|---|
| **Index** | name · columns or expressions with order (ASC/DESC, NULLS FIRST/LAST) · method (btree, hash, gin, gist, brin, spgist) · unique · INCLUDE columns · partial `WHERE` · fillfactor · **concurrently** (default on for tables over a size threshold) · tablespace |
| **Foreign key** | name · local columns ↔ referenced schema.table(columns), with an auto-suggested matching PK / unique key · ON UPDATE / ON DELETE (NO ACTION, RESTRICT, CASCADE, SET NULL, SET DEFAULT) · MATCH · DEFERRABLE / INITIALLY DEFERRED · **create as NOT VALID, then validate** · optional supporting index on the local columns (and a warning when one is missing) |
| **Check** | name · expression (SQL editor field) · NOT VALID · NO INHERIT |
| **Unique / PK** | name · columns · NULLS NOT DISTINCT (PG 15+) · INCLUDE · use an existing unique index |
| **Exclusion** | name · method · element/operator pairs · WHERE |
| **Column** | name · type (catalog-driven picker: base types, domains, enums, arrays) · length/precision · NOT NULL · default · identity (ALWAYS / BY DEFAULT) or generated (STORED) · collation · comment; for type changes, the `USING` expression |
| **Trigger** | name · timing (BEFORE / AFTER / INSTEAD OF) · events (INSERT / UPDATE [OF cols] / DELETE / TRUNCATE) · FOR EACH ROW / STATEMENT · WHEN condition · function picker (`RETURNS trigger` functions), with "New trigger function…" opening a template in a query tab |

Every dialog has a **SQL preview** pane that updates as you type, plus **Apply** and
**Open in query tab**.

## 3. How it works

### 3.1 Core (no UI, all unit or live tested)

| Component | What it does |
|---|---|
| `TableInspector` | Catalog reads into records. **Indexes:** `pg_index` + `pg_get_indexdef`, size, `pg_stat_user_indexes` scans. **Constraints:** `pg_constraint` (p/f/u/c/x) + `pg_get_constraintdef`, `convalidated`, FK actions, deferrable, referenced table. **Triggers:** `pg_trigger` without internal ones, + `pg_get_triggerdef`, enabled state. **Columns:** identity, generated, collation and comments. **Referenced by:** foreign keys elsewhere that point at this table. **Ownership and privileges.** |
| `DdlBuilder` | Pure functions: a change → SQL (`CREATE INDEX`, `ALTER TABLE … ADD CONSTRAINT`, `RENAME`, `DROP`, `ALTER COLUMN … TYPE … USING`, `COMMENT ON`, …). Always quotes identifiers. Golden-file unit tests per statement type. |
| `ChangeSet` + `DdlApplier` | Runs a list of changes in **one transaction** with `SET LOCAL lock_timeout` (e.g. 5 s, so it never queues behind long transactions and blocks the table), and rolls back on any error. Statements that can't run in a transaction (`CREATE/DROP INDEX CONCURRENTLY`, `REINDEX … CONCURRENTLY`) run alone afterwards, and the user is told. Uses the existing `QueryExecutor` / `DdlPredicates` machinery. |
| `DependencyInspector` | `pg_depend` / `pg_constraint` walk: what a drop or type change would break (views, FKs, triggers, functions), shown before confirming. Offers `CASCADE` only when explicitly ticked. |
| `scriptTableCreate` (rewrite) | Full CREATE output, close to what pg_dump writes for one table: columns with identity/generated, constraints, indexes, triggers, comments, ownership. |

### 3.2 Safety rules
- **Preview first:** nothing runs without showing the SQL, and every change can be sent
  to a query tab instead.
- **Show lock impact:** each statement shows the lock it takes (e.g. "ACCESS EXCLUSIVE:
  blocks reads and writes on `orders`"), from a small lookup table.
- **Large tables** (row estimate or size above a setting): index create/drop defaults to
  CONCURRENTLY, and FK/check constraints default to `NOT VALID` followed by a separate
  `VALIDATE`.
- **Permissions:** actions are greyed out with a tooltip when the login doesn't own the
  table (and isn't a superuser), matching the existing "only a superuser sees everything"
  rule.
- **Drops:** confirmed by typing the name, with dependencies listed first.

### 3.3 App (UI)
- New `ExplorerNode.Kind` values: KEYS_FOLDER, PRIMARY_KEY, FOREIGN_KEY,
  CONSTRAINTS_FOLDER, CHECK, UNIQUE, EXCLUSION, INDEXES_FOLDER, INDEX, TRIGGERS_FOLDER,
  TRIGGER, REFERENCED_BY_FOLDER, REFERENCING_FK.
- New loaders in `ObjectExplorer`, new icons (key, link, list-checks, zap, …), and
  context menus in `MainFrame.buildContextMenu`.
- `ui/design/`: the `TableDesignerTab` plus one dialog per object, sharing a
  `SqlPreviewPane` (read-only RSyntaxTextArea) and a `ColumnPicker`.
- After any applied change, refresh only the affected table's node (not the whole tree)
  and show the result in the Output panel.

## 4. Phases

| # | Phase | Delivers | Est. |
|---|---|---|---|
| 1 | **Read side** | `TableInspector`; Keys / Constraints / Indexes / Triggers / Referenced-by folders; full Script as CREATE / DROP; "View dependencies" | 1.5 d |
| 2 | **Change engine** | `DdlBuilder`, `ChangeSet` + `DdlApplier` (transaction, lock_timeout, concurrent handling), `DependencyInspector`, SQL preview pane | 1.5 d |
| 3 | **Object dialogs** | Create / edit / rename / drop / validate for indexes, FKs, PK, unique, check, exclusion; add / alter / rename / drop columns; triggers (create, enable / disable, drop); table rename / truncate / drop | 3 d |
| 4 | **Table designer** | Designer tab with grids, live SQL, Apply / Discard / Validate; New table… | 2 d |
| 5 | **Safety and large tables** | Lock-impact labels, CONCURRENTLY / NOT VALID defaults, permission greying, dependency-aware drops | 1 d |
| 6 | **Tests and docs** | DdlBuilder golden tests; live tests per object type on a throwaway database; E2E steps (add index, add FK, drop both from the tree) on Windows and the CI Mac; CLAUDE.md | 1 d |

**About 10 working days.** Phases 1–3 alone (about 6 days) deliver right-click management
of every object type. The designer (phase 4) is the SSMS-style extra.

## 5. Testing approach
- **Unit:** every `DdlBuilder` statement type, compared against expected SQL, including
  quoting of odd names (`"Order Items"`, mixed case, reserved words).
- **Live** (`@PostgresAvailable`, throwaway database each run):
  - create a table, then add, verify, rename and drop each object type
  - FK with every ON DELETE action
  - NOT VALID followed by validate
  - CONCURRENTLY outside the transaction
  - lock_timeout firing while another session holds a lock
  - a failing change set rolling back completely
- **E2E:** right-click a table, then New index…, Apply; the node appears; Drop, confirm,
  and it's gone. Runs in light and dark on Windows and macOS CI.

## 6. Decisions for you
1. **Scope of the first delivery:** right-click dialogs only (phases 1–3, recommended
   first), or the table designer tab too?
2. **Row data:** this plan covers *structure*. Do you also want to **edit table data**
   (insert / update / delete rows directly in the result grid, keyed by the primary key)?
   That would be a separate phase of about 2 days.
3. **Apply behaviour:** always show the SQL preview before running (recommended), or allow
   "apply immediately" for small, safe changes like rename or comment?
4. **Large-table threshold** for defaulting to CONCURRENTLY / NOT VALID: recommend
   100,000 rows or 100 MB.

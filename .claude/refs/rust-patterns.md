# Rust Code Standards (frap-core)

<!-- section:basics -->

## Idiomatic, flat, atomic

- One function does one thing; keep nesting shallow — prefer early `return`/`continue` and iterator chains
  (`.iter().filter().map().collect()`) over nested `for`/`if`.
- Public API is deliberate: only `pub` what callers need. Internal helpers stay private (`fn`, no `pub`).
- Re-exports live in `crates/core/src/lib.rs` (`pub use`). **Do not change the signature of an already
  re-exported public function** without an explicit migration note — Java DTOs and contract tests bind to it.
- Prefer borrowing (`&str`, `&[T]`, `&Snapshot`) over owned args. Clone only at the boundary where you must.
- Derive, don't hand-roll: `#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]`.
- Naming: `snake_case` fns/fields, `CamelCase` types, `SCREAMING_CASE` consts. Match the surrounding file.

```rust
// GOOD: flat, borrows, iterator chain
pub fn build_element_map(snapshot: &DOMSnapshot, options: &MapOptions) -> ElementMap {
    let elements = snapshot
        .elements
        .iter()
        .filter(|e| options.include_non_interactive || is_interactive(e))
        .map(|e| node_from(e, &snapshot.elements))
        .collect();
    ElementMap { elements, ..Default::default() }.with_metadata_counts()
}
```

## Serde contract discipline

- snake_case wire format (`#[serde(rename_all = "snake_case")]` on enums; field names already snake_case).
- **Add fields backward-compatibly**: new optional field → `#[serde(default)] pub value: Option<String>`.
  Never reorder/rename existing fields — the Java Jackson records and `tests/fixtures/**/expected.json` read
  them by name.
- A `Vec<T>` that may be absent → `#[serde(default)]`. Skip empties with
  `#[serde(skip_serializing_if = "Option::is_none")]` to keep output stable.

## Errors

- Fallible JSON-boundary fns return `Result<_, CoreError>` and use `?`. Don't `unwrap()`/`expect()` on
  external input; `unwrap()` is acceptable only on `write!`/`writeln!` into a `String` (infallible).
- No `panic!` in library code paths reachable from RPC input.

<!-- /section:basics -->

<!-- section:perf -->

## Avoid accidental O(n²)

When a per-element computation needs page-wide context (e.g. "is this attribute value unique?"), build an
index **once** before the loop, not a rescan per element.

```rust
// GOOD: one pass to count, O(1) lookup per element
let mut counts: HashMap<&str, usize> = HashMap::new();
for e in elements { if let Some(h) = e.attributes.get("href") { *counts.entry(h).or_default() += 1; } }
let is_unique = |h: &str| counts.get(h).copied().unwrap_or(0) == 1;
```

Strings: prefer `&str` keys into the existing `elements` slice over cloning into owned `String` maps.

<!-- /section:perf -->

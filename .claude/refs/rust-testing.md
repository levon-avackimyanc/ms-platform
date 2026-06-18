# Rust Testing Standards (frap-core)

<!-- section:structure -->

## Unit tests live next to code

- `#[cfg(test)] mod tests { use super::*; ... }` at the bottom of the module under test.
- One `#[test]` per behaviour; name says what holds: `cascade_picks_href_when_unique`,
  `entropy_rejects_styled_component_hash`. No `test_` noise prefix unless the file already uses it.
- Arrange / act / assert, flat. Build small synthetic `DOMElementInfo`/`DOMSnapshot` inline — no I/O, no
  network. Assert on the meaningful field, with a message: `assert_eq!(node.locator.strategy, "href", "...")`.
- Table-driven for classifiers: iterate `&[(input, expected)]` and assert each — keeps entropy/translit
  detectors honest across many cases in one test.

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cascade_picks_href_when_unique() {
        let snap = snapshot(vec![link("/person", "Частным клиентам")]);
        let map = build_element_map(&snap, &MapOptions { include_non_interactive: true, ..Default::default() });
        assert_eq!(map.elements[0].locator.strategy, "href");
        assert!(map.elements[0].confidence >= 0.8);
    }
}
```

## Determinism

- No `Date`/time/random in assertions. If a struct carries `timestamp_ms`, assert other fields, not it.
- Confidence/threshold assertions use `>=`/`<=` against the contract number, not float `==`.

## Gates (must pass)

`cargo test --workspace` · `cargo clippy --workspace -- -D warnings` · `cargo fmt --check`.

<!-- /section:structure -->

<!-- section:integration -->

## Integration / contract tests

- Live in `crates/core/tests/*.rs` (separate compilation unit; only the crate's **public** API is visible).
- Fixtures under `crates/core/tests/fixtures/<group>/` (or `fixtures/contract/<group>/`), loaded via
  `PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(...)` + `fs::read_to_string`.
- Pattern: a `request.json`/`snap-*.json` input + an `expected.json` of **thresholds**, deserialized into a
  small `#[derive(Deserialize)] struct Expected { ... }`. The test runs the real engine
  (`build_element_map`, `generate_page_object`) and asserts the output meets each threshold.
- Commit a **trimmed, deterministic** real-page snapshot as the fixture; pin its composition in a comment so
  the metric counts are stable (percentages on tiny samples are noisy — assert absolute counts).
- A new `expected.json` schema is read only by its own test unless a cross-language contract is intended.

<!-- /section:integration -->

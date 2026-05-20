# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 1/24 (4.2%)
- **Function parity:** 5/369 matched (target 25) — 1.4%
- **Class/type parity:** 4/82 matched (target 25) — 4.9%
- **Combined symbol parity:** 9/451 matched (target 50) — 2.0%
- **Average inline-code cosine:** 0.31 (function body across 1 matched files)
- **Average documentation cosine:** 0.84 (doc text across 1 matched files)
- **Cheat-zeroed Files:** 0
- **Critical Issues:** 1 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. error

- **Target:** `age.Error`
- **Similarity:** 0.31
- **Dependents:** 1
- **Priority Score:** 1000906.9
- **Functions:** 5/5 matched (target 25)
- **Missing functions:** _none_
- **Types:** 4/4 matched (target 25)
- **Missing types:** _none_

## Success Criteria

For each file to be considered "complete":
- **Similarity ≥ 0.85** (Excellent threshold)
- All public APIs ported
- All tests ported
- Documentation ported
- port-lint header present

## Next Commands

```bash
# Initialize task queue for systematic porting
cd tools/ast_distance
./ast_distance --init-tasks ../../tmp/age/src rust ../../src/commonMain/kotlin/io/github/kotlinmania/age kotlin tasks.json ../../AGENTS.md

# Get next high-priority task
./ast_distance --assign tasks.json <agent-id>
```
## Reexport / Wiring Modules

These files match `reexport_modules` patterns in `.ast_distance_config.json`. They are filtered out of
normal priority and missing-file ladders because they are wiring
modules, not direct logic ports. Consult them for call-site routing;
do not treat them as the next implementation target by default.

### Missing

| Source | Expected target | Deps | Source path | Expected path |
|--------|-----------------|------|-------------|---------------|
| `lib` | `Lib` | 0 | `lib.rs` | `Lib.kt` |

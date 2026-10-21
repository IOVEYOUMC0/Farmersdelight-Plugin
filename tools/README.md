# Repository contract checks

Nine checks that enforce the machine-checkable half of this project's maintenance contracts. They all run in
CI (the `lint` job in `.github/workflows/ci.yml`) on every push and pull request, and all of them block.

| Check | Guards |
| --- | --- |
| `strip_ce_comments.py --check` | Shipped CraftEngine configuration under `src/main/resources/craftengine/**/configuration/` carries no comments. Field references live in the wiki, not in the data files. |
| `meal_icons.py --check` | Every `configuration/meal_icons.yml` entry still corresponds to a cooking pot result that exists, and every such result has an entry with a resolvable 16x16 texture. |
| `check_lang_keys.py` | Every language key the Java sources reference is defined in both `lang/en_us.yml` and `lang/zh_cn.yml`, the two locales stay symmetric, and no value is blank. |
| `check_api_boundary.py --quiet` | Addons compile against `api.**` only, and no public api signature exposes an internal type without the `@ApiStatus.Internal` marker. |
| `check_config_paths.py --quiet` | Every literal config path the code reads exists in a shipped file (an intentional exception marks itself with `config-path-check: no shipped key, on purpose`). |
| `check_duplicate_yaml_keys.py --quiet` | No shipped YAML file under `src/main/resources/**` repeats a key inside one mapping. It walks `yaml.compose` node trees, because `yaml.safe_load` silently keeps the last value (a duplicate block, not a load error) and CraftEngine's loader is duplicate-tolerant too. Files that are not single-document strict YAML are skipped and named, never reported as duplicates. |
| `check_block_state_occupancy.py` | No vanilla block state is pinned by **two different** custom blocks under `src/main/resources/craftengine/**` — two blocks on one state are indistinguishable in game. One block reusing a state across its own faces is normal and is listed separately; `auto_state` is ignored; `block-state-mappings` is reported as a hint. |
| `check_pack_client_keys.py --quiet` | Every client-bound translation key the pack references is defined in every shipped pack locale, so no tooltip falls back to the raw key. |
| `check_verification_evidence.py --quiet` | Every round manifest it is pointed at keeps the round shape: a real date, a non-empty `scope` and `limitations`, artifacts with `sha256` and `bytes`, and runs with `environment`/`java`/`craft_engine` plus `PASS`/`SKIP`/`FAIL` checks. It adds no facts of its own, so a round without a manifest is not checked. |

All nine are read-only and exit non-zero on a real problem, so no separate assertion is needed. They need
PyYAML (`python -m pip install pyyaml`).

```bash
python tools/strip_ce_comments.py --check
python tools/meal_icons.py --check
python tools/check_lang_keys.py --quiet
python tools/check_api_boundary.py --quiet
python tools/check_config_paths.py --quiet
python tools/check_duplicate_yaml_keys.py --quiet
python tools/check_block_state_occupancy.py        # add --quiet for the summary only
python tools/check_pack_client_keys.py --quiet
python tools/check_verification_evidence.py --quiet
```

Without `--check`, `strip_ce_comments.py` and `meal_icons.py` rewrite the files instead of reporting. That is
the intended way to fix what they find; both keep existing order and only add, prune or de-comment.

## Self-tests and the total runner

`test_checks.py` proves each check above can still fail. It builds a throwaway module tree, puts one violation
in it, requires the check to exit non-zero and name the offending place, then repairs the tree and requires exit
zero — so a rule that is removed or weakened turns the self-test red with it. `selftest_support.py` holds the
fixture plumbing the classes share, and a check locates its module root from its own file, which is why the
fixture tree gets its own `tools/` copy of the script under test.

```bash
python tools/run_all_checks.py            # every check + the self-tests + the verification matrix
python tools/run_all_checks.py --quiet    # verdicts only
python -m unittest test_checks            # the self-tests alone (run from this directory)
```

`run_all_checks.py` exits non-zero when any step fails, so the whole set is one gate.

## The verification matrix

The matrix records which claims have been verified. It is a verification record, so it lives outside this
repository (in the workspace's `verification-records/`); `tools/check_verification_matrix.py` reads it without
touching anything: every row needs the seven columns, a conclusion from a fixed set, and — for a row that
claims a pass — a reference that resolves. The script finds the plugin repository from its own path
(`<repo>/tools/...`); it reads `docs/verification.md` when that file exists, reports nothing to check
otherwise, and takes a matrix kept elsewhere with `--dir`:

```bash
python tools/check_verification_matrix.py --dir ../verification-records
```

| Reference | Resolves against |
| --- | --- |
| `test:<class>` | this repository's `src/test/java`, the class name split into directories plus `.java` |
| `doc:<path>` | this repository's root, so the evidence is versioned with the code it describes |

`run_all_checks.py` runs this check when the repository has a matrix of its own; a checkout without one counts
as nothing to check.

## Round manifests

A round manifest is the machine-readable half of a verification round: which binaries were exercised (sha256
and size), on which environments (core build, Java, CraftEngine build), what each check reported
(`PASS`/`SKIP`/`FAIL`) and what the round does not cover (`scope` + `limitations`). The prose records and the
manifests both live outside this repository.

Fields: `date` (a real `YYYY-MM-DD`), `scope` (what the round covers), `limitations` (what it does not),
`artifacts` (name → `sha256` + `bytes`) and `runs` (each with `environment`, `java`, `craft_engine`, optional
`core_build` and `craft_engine_sha256`, and a `checks` map of name → `status` (`PASS`/`SKIP`/`FAIL`) + `detail`).

The manifests are operational records, not source: the repository tracks only the format and this check, while
the per-round files live in the workspace's `verification-records/` directory. The check reads a repository
copy by default — normally empty — and an external directory only when asked:

```bash
python tools/check_verification_evidence.py --dir ../verification-records
```

`tools/check_verification_evidence.py` enforces the shape and permits unknown extra keys, so a manifest can
carry more detail than the check understands. It never adds data, which is why a round without a manifest is
not checked — the format applies to new rounds, not back-filled onto old ones.

## Scope

These copies are scoped to this repository: they check what this repository ships, so the same commands run in
this repository's CI. The addons' packs and language files live in their own repositories and are not scanned
here.

## Related tools outside this repository

`check_ce_symbols.py`, `check_item_names.py`, `check_corndelight_pack.py`, `check_endsdelight_pack.py` and
`audit_jar_backdoor.py` need inputs that are not part of this repository (CraftEngine release jars, the original
mod's language files, the standalone packs), so they are not part of this repository's CI.

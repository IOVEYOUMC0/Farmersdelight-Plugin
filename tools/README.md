# Repository contract checks

Seven checks that enforce the machine-checkable half of this project's maintenance contracts. They run in CI
(the `lint` job in `.github/workflows/ci.yml`) on every push and pull request — except the last one, which
reports without blocking (see its row).

| Check | Guards |
| --- | --- |
| `strip_ce_comments.py --check` | Shipped CraftEngine configuration under `src/main/resources/craftengine/**/configuration/` carries no comments. Field references live in the wiki, not in the data files. |
| `meal_icons.py --check` | Every `configuration/meal_icons.yml` entry still corresponds to a cooking pot result that exists, and every such result has an entry with a resolvable 16x16 texture. |
| `check_lang_keys.py` | Every language key the Java sources reference is defined in both `lang/en_us.yml` and `lang/zh_cn.yml`, the two locales stay symmetric, and no value is blank. |
| `check_api_boundary.py --quiet` | Addons compile against `api.**` only, and no public api signature exposes an internal type without the `@ApiStatus.Internal` marker. |
| `check_config_paths.py --quiet` | Every literal config path the code reads exists in a shipped file (an intentional exception marks itself with `config-path-check: no shipped key, on purpose`). |
| `check_duplicate_yaml_keys.py --quiet` | No shipped YAML file under `src/main/resources/**` repeats a key inside one mapping. It walks `yaml.compose` node trees, because `yaml.safe_load` silently keeps the last value (a duplicate block, not a load error) and CraftEngine's loader is duplicate-tolerant too. Files that are not single-document strict YAML are skipped and named, never reported as duplicates. |
| `check_block_state_occupancy.py` | No vanilla block state is pinned by **two different** custom blocks under `src/main/resources/craftengine/**` — two blocks on one state are indistinguishable in game. One block reusing a state across its own faces is normal and is listed separately; `auto_state` is ignored; `block-state-mappings` is reported as a hint. Read-only, and **reported without blocking** in CI (`continue-on-error`) until the existing list is dispositioned. |

All seven are read-only and exit non-zero on a real problem, so no separate assertion is needed. They need
PyYAML (`python -m pip install pyyaml`). Only `check_block_state_occupancy.py` is non-blocking: it has a
known, not-yet-triaged list, and its CI step carries `continue-on-error: true` with a comment to remove that
once the list is empty.

```bash
python tools/strip_ce_comments.py --check
python tools/meal_icons.py --check
python tools/check_lang_keys.py --quiet
python tools/check_api_boundary.py --quiet
python tools/check_config_paths.py --quiet
python tools/check_duplicate_yaml_keys.py --quiet
python tools/check_block_state_occupancy.py        # add --quiet for the summary only
```

Without `--check`, `strip_ce_comments.py` and `meal_icons.py` rewrite the files instead of reporting. That is
the intended way to fix what they find; both keep existing order and only add, prune or de-comment.

## Scope

These copies are scoped to this repository. The monorepo parent directory holds workspace-level copies of three
of them — `strip_ce_comments.py`, `meal_icons.py` and `check_lang_keys.py` — which check every module in one pass:

* `strip_ce_comments.py` there also walks the addons' craftengine packs and the standalone packs under `packs/`;
* `meal_icons.py` there also covers the `crabbersdelight`, `brewinandchewin`, `endsdelight`, `corndelight` and
  `festivaldelicacies` namespaces;
* `check_lang_keys.py` there also checks the addons' language files and resolves the cross-module
  `<namespace>.<suffix>` key form.

The addons' packs and language files live in their own repositories, so a change here cannot affect them; the
split is what makes these checks runnable in this repository's CI at all.

## Related tools that stay workspace-level

`check_ce_symbols.py`, `check_item_names.py`, `check_corndelight_pack.py`, `check_endsdelight_pack.py` and
`audit_jar_backdoor.py` need inputs that are not in this repository (CraftEngine release jars, the original
mod's language files, the standalone packs). They stay in the monorepo parent directory's `tools/`.

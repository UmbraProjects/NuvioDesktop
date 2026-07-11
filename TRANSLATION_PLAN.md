# Translation completion plan

This plan deliberately does not generate or modify translations. It defines a safe workflow for completing them later with a lower-cost model and human review.

## Current baseline

- English source: `composeApp/src/commonMain/composeResources/values/strings.xml`
- English resource keys: 1,984
- App languages: Czech, French, German, Greek, Indonesian, Italian, Norwegian, Polish, Portuguese, Spanish, and Turkish, plus English
- Existing locale coverage ranges from roughly 53% to 87%. French is currently the most complete at 1,720 keys; Greek has the largest gap at 935 missing keys.
- `values-id` and `values-in` currently contain the same number of Indonesian entries. The app selects `id`, so `values-in` should be verified as an obsolete compatibility copy before it is removed or generated from the canonical file.
- Resource parity alone is not sufficient. There are user-facing strings in Kotlin, the desktop player HTML/JavaScript, and native-player messages that need an extraction audit.

## Phase 1: inventory and freeze

1. Freeze the English resource file for the translation run, or record its Git commit so later additions can be handled as a delta.
2. Generate a machine-readable manifest containing each key, English text, placeholders, feature prefix, and translator notes.
3. Scan Kotlin, JavaScript, HTML, and native bridge sources for user-facing literals. Move confirmed UI text into Compose resources before translating.
4. Identify keys that are obsolete, duplicated, or misleadingly named. Rename only before translation begins so model output is not wasted.
5. Establish a short glossary for product names and domain terms such as Nuvio, Trakt, SIMKL, debrid, add-on, stream, scrobble, hero, subtitle, and Rich Presence.

## Phase 2: automated safety tooling

Create a validation script that runs locally and in CI. It should fail on:

- missing or unexpected resource keys;
- duplicate keys;
- malformed XML or invalid escapes;
- changed, missing, or reordered format placeholders such as `%1$s` and `%2$d`;
- accidental translation of product names, URLs, file extensions, shortcut keys, or JSON fragments;
- blank translations or translations identical to English unless explicitly allowlisted;
- unsupported locale directory names.

The script should also produce per-locale coverage and a small review report grouped by feature prefix.

## Phase 3: model translation batches

1. Translate one locale at a time and one feature-sized batch at a time, rather than sending the full XML file in a single prompt.
2. Use structured input/output containing the resource key, source text, placeholders, context, and glossary rules. Ask the model to return values only, never reconstructed XML.
3. Reassemble XML with a deterministic script so key order, escaping, and formatting remain controlled by the repository rather than the model.
4. Run the validator after every batch. Reject the entire batch if placeholder or XML validation fails.
5. Translate missing keys first. Review existing translations separately so completion work does not silently rewrite established terminology.
6. Suggested rollout order: French, Polish, Indonesian, Italian/Portuguese/Turkish, Norwegian/Czech, German/Spanish, then Greek. This gets the nearly complete locales over the line first while preserving a repeatable process for the larger gaps.

## Phase 4: linguistic review

For each locale:

1. Review high-visibility surfaces first: onboarding, navigation, playback controls, errors, account actions, destructive confirmations, and settings search.
2. Check terminology consistency against the glossary and across related title/description pairs.
3. Have a fluent reviewer inspect a generated change report rather than the whole XML file.
4. Record intentional English fallbacks and untranslatable brand terms in an allowlist.

## Phase 5: UI and runtime QA

1. Add a pseudo-locale with expanded text to expose clipping, fixed-width assumptions, and missing resource lookups.
2. Smoke-test every app language in compact and full desktop layouts.
3. Exercise the player overlay, subtitle/audio dialogs, stream selection, details page, settings search, onboarding, and error dialogs.
4. Verify diacritics, non-ASCII text, sorting, capitalization, interpolation, and fallback behavior.
5. Capture screenshots of a fixed set of screens per locale for quick visual comparison.

## Phase 6: keeping translations current

- CI should report resource parity on every pull request.
- New English keys should require either translations or an explicit fallback marker.
- Run small translation deltas regularly instead of another large backlog.
- Keep translator notes and the glossary versioned beside the validation script.

## Completion criteria

A locale is complete when it has 100% key parity, passes placeholder/XML validation, has no unreviewed high-visibility strings, and passes the desktop smoke-test checklist. Translation quality fixes can continue after that without blocking resource completeness.

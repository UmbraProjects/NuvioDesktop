#!/usr/bin/env bash
# Checks a merge of windows-tv-adaptive into Spyder before it is pushed.
#
#   scripts/check-merge.sh <spyder-commit-before-merge>
#
# Run it in the merge checkout, after `git merge --no-ff --no-commit` (or after committing the merge).
# It looks at the working tree against the given commit and exits non-zero when something the fork owns
# was lost. It reads only; it never changes a file. See claude/merging-upstream.md for the full routine.
set -u

before="${1:-}"
if [ -z "$before" ]; then
  echo "usage: scripts/check-merge.sh <spyder-commit-before-merge>" >&2
  exit 2
fi
git rev-parse --verify --quiet "$before^{commit}" >/dev/null || { echo "not a commit: $before" >&2; exit 2; }

fail=0
bad() { echo "FAIL: $*"; fail=1; }
ok()  { echo "ok:   $*"; }

# 1. Spyder-only workflows must still exist (a merge can delete them and then no build starts).
for wf in .github/workflows/windows-build.yml .github/workflows/android-remote.yml; do
  if [ -f "$wf" ]; then ok "$wf exists"; else bad "$wf is missing: git checkout $before -- $wf"; fi
done

# 2. The updater's three fork values and their marker comment.
updater=composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/AppUpdater.kt
if [ -f "$updater" ]; then
  grep -q 'gitHubOwner *= *"spydermahn1234"'   "$updater" && ok "updater gitHubOwner"        || bad "updater gitHubOwner is not spydermahn1234"
  grep -q 'releaseChannelBranch *= *"Spyder"'  "$updater" && ok "updater releaseChannelBranch" || bad "updater releaseChannelBranch is not Spyder"
  grep -q 'NuvioHtpcRepoUrl *= *"https://github.com/spydermahn1234/NuvioDesktop"' "$updater" \
    && ok "updater NuvioHtpcRepoUrl" || bad "updater NuvioHtpcRepoUrl is not the fork"
  grep -q 'fork:updater' "$updater" && ok "updater fork:updater comment" || bad "updater lost its fork:updater comment"
else
  bad "$updater is missing"
fi

# 3. Files deleted by the merge (review by eye: anything unexpected is a problem).
deleted=$(git diff --name-status "$before" -- . ':!*.md' 2>/dev/null | grep '^D' || true)
if [ -n "$deleted" ]; then
  echo "NOTE: files deleted since $before (check each is intended):"
  echo "$deleted" | sed 's/^/        /'
else
  ok "no files deleted"
fi

# 4. Fork-only files changed by the merge. Anything listed was touched by upstream's side: read it and ask.
changed=$(git diff --name-only "$before" 2>/dev/null || true)
forkonly=$(echo "$changed" | grep -E \
  '/features/remote/|^remote-android/|features/music/(MusicNowPlaying|NowPlaying|MusicAlbumScreen|AlbumLenses|AlbumEssentials|AlbumInBrief|AlbumLensEdit|MusicItemMenu|MusicRadio)|features/music/(visualiser|booklet|library|home|nebula)/|features/music/library/|settings/(PhoneRemote|MusicFacts|MusicPlayLog)' \
  || true)
if [ -n "$forkonly" ]; then
  echo "NOTE: fork-only UI/logic files changed (list is a pattern match; CLAUDE.md has the full list):"
  echo "$forkonly" | sed 's/^/        /'
  echo "      Read what changed and ask the user before keeping upstream's version."
else
  ok "no fork-only files changed"
fi

# 5. CI test filters must still point at packages that exist.
wf=.github/workflows/windows-build.yml
if [ -f "$wf" ]; then
  for pkg in features/music features/preroll core/network features/remote; do
    if ls -d composeApp/src/*/kotlin/com/nuvio/app/$pkg >/dev/null 2>&1; then ok "test package $pkg exists"; else bad "test package $pkg no longer exists: fix the --tests filters"; fi
  done
fi

# 6. Marker counts: the fork strings should not have been replaced by upstream's owner name.
echo "info: spydermahn1234 matches: $(git grep -c spydermahn1234 -- . ':!CHANGELOG.md' ':!*.md' 2>/dev/null | awk -F: '{s+=$2} END{print s+0}')"
echo "info: UmbraProjects matches:  $(git grep -c UmbraProjects  -- . ':!CHANGELOG.md' ':!*.md' 2>/dev/null | awk -F: '{s+=$2} END{print s+0}') (compare with the Spyder side: git grep at $before)"

if [ "$fail" -ne 0 ]; then
  echo "RESULT: problems found"
  exit 1
fi
echo "RESULT: required checks passed (read the NOTE lines above)"

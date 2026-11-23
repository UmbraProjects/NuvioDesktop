@AGENTS.md

## On `windows-tv-adaptive` (UmbraProjects' branch)

Work in this checkout; there is no linked PC. The checkout has Windows line endings (`core.autocrlf=true`): if
`git status` ever lists hundreds of changed files, check `git diff --ignore-cr-at-eol --stat` before touching anything.

## Testing checklist: keep TESTING-UMBRA.md up to date

Every feature or fix on this branch adds its checks to `TESTING-UMBRA.md` (one thing to try on the TV per line) in the
same commit, recorded the way its header says. Never leave a "Not yet tried" CHANGELOG entry without matching checks
there. `TESTING.md` is Nick's checklist for his fork; leave it to him.

## Merging with Spyder's branch

Before merging, cherry-picking or opening a PR between Spyder's branch (Nick's HTPC fork) and this one, read
`SPYDER-FORK-UI.md` and warn the user about the fork-only UI it lists before it goes in. A merge from Spyder conflicts
on this file: keep this short version, and copy any additions to Nick's fork-UI section into `SPYDER-FORK-UI.md`.

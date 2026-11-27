@AGENTS.md

## On `windows-tv-adaptive` (UmbraProjects' branch)

Work in this checkout; there is no linked PC. The checkout has Windows line endings (`core.autocrlf=true`): if
`git status` ever lists hundreds of changed files, check `git diff --ignore-cr-at-eol --stat` before touching anything.

## Testing checklists

`TESTING.md` is Nick's checklist for his fork; leave it to him. `TESTING-UMBRA.md` is a local, rarely read leftover:
don't add checks to it or point CHANGELOG entries at it. A CHANGELOG entry nobody has tried yet ends with "Not yet tried."

## Merging with Spyder's branch

Before merging, cherry-picking or opening a PR between Spyder's branch (Nick's HTPC fork) and this one, read
`SPYDER-FORK-UI.md` and warn the user about the fork-only UI it lists before it goes in. A merge from Spyder conflicts
on this file: keep this short version, and copy any additions to Nick's fork-UI section into `SPYDER-FORK-UI.md`.

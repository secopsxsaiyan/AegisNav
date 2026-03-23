# workflow.md — AegisNav

## Development Methodology
- Feature-driven development with AI-assisted implementation
- Full code review workflow: FeatureMapper → TriadVerifier (self-healing loop, max 5 iters) → AlignmentChecker → PRManager
- JSON-only communication between workflow stages

## Repository Strategy

### Two-Repo Model
- **dev-AegisNav** (`/workspace/dev-AegisNav`) — ALL changes go here
  - Remote: `tea.pasubis.com/o4o793uys/dev-AegisNav`
- **WatchTheWatcher** (`/workspace/WatchTheWatcher`) — release mirror, NEVER touched without explicit user approval
  - History cleaned (orphan commit) — no git history visible

### Never Push to Release
These items exist in dev only and must never appear in the release repo:
- `tools/`, `scripts/`, `backend/`, `.gitea/`, `.pre-commit-config.yaml`
- `app/src/test/`, `app/src/androidTest/`
- `alpr_tn.geojson`
- Debug code, bare `Log.*()` calls
- Sentry/GlitchTip SDK and all related code

## Git Conventions
- **Commit messages**: Descriptive, present tense
- **Shortcut alias** (dev-AegisNav only, NEVER on WatchTheWatcher):
  ```bash
  alias gsync="cd /workspace/dev-AegisNav && git add . && git commit -m auto && git push origin main"
  ```

## Code Quality Requirements

### All Changes Must:
1. Include unit tests for new features
2. Pass `./gradlew test` before commit
3. Use `AppLog` instead of bare `Log.*()` calls
4. Respect offline-only architecture (no undisclosed HTTP calls)
5. Follow existing Kotlin conventions, coroutine patterns, Hilt + Room architecture

### Security Standards
- ProGuard/R8 hardened release builds
- Security audit target: 0 critical, 0 high, 0 medium in release
- Snyk: 0 vulnerabilities
- Force-upgrade vulnerable transitive deps
- No world-readable files

### Before Merging to Release
- Strip Sentry SDK and all crash reporting code
- Remove all dev-only files (tools, tests, debug configs)
- Full device test pass on both S21 and Pixel Fold

## CLI Rules
- **Ask before executing** — list all commands before running
- **Use full paths** for CLI commands
- **Subagent model**: `anthropic/claude-sonnet-4-6` — no exceptions unless user overrides

## Device Testing
- **Prefer `adb install -r`** to preserve app data (tiles/geocoder/routing)
- Uninstall only for clean installs
- Test on both devices: S21 (API 35) + Pixel Fold (API 36)
- Fold requires Location ON before testing

## Version Bumping
- `versionName` format: `YYYY.MM.DD`
- `versionCode`: monotonically increasing integer
- Current: versionName `2026.03.19`, versionCode `7`
- DB version: `28` (increment for schema changes)

## Deployment
- **Current**: Manual APK build + `adb install`
- **Planned**: GitHub release + F-Droid (Phase 10)
- **Data assets**: Built via `tools/build_all_states.sh`, uploaded to Gitea/GitHub releases

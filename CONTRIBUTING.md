# Contributing

The rules for coding agents are in [AGENTS.md](AGENTS.md); this file covers how to work on Floridex as a person.

## Setup

```bash
git clone https://github.com/iDiagoValeta/floridex
cd floridex
git config commit.template .gitmessage
```

- Web: Node 24 and pnpm. `cd web && pnpm install && pnpm dev`.
- Android: JDK 17 and the Android SDK. `cd android && ./gradlew assembleDebug`.
- Model: Python 3.12, [uv](https://docs.astral.sh/uv/) and an NVIDIA GPU for training. `cd ml && uv sync`. The datasets are downloaded by the `prepare*.py` scripts and are not in the repository.

The README explains how the three parts fit together.

## Checks

```bash
cd web && pnpm build                                      # type check and production build
cd android && ./gradlew assembleDebug assembleDebugAndroidTest
uvx ruff check ml
```

CI runs those three. The end-to-end checks (real photos through the UI in Chromium and WebKit, and the Android instrumented tests on a device) need the test photos, which come from the datasets and are not published: generate them with `shared/muestra.py` as the README describes, and run them before a change that touches identification, storage or the camera.

User-visible text is peninsular Spanish (tuteo). Everything else in git is English: code comments, script output, docs, commits, issues and PRs.

## Workflow

Trunk-based. `main` is the only long-lived branch and is protected.

1. Open an issue first.
2. Branch from `main`, named `<prefix>/GH-<issue>-<context>`, for example `bugfix/GH-12-camera-stays-on`. Allowed prefixes: `feature`, `bugfix`, `hotfix`, `improvement`, `refactoring`, `documentation`, `dependency`, `deprecation`, `removal`, `revert`, `security`, `internal`, `automated`. `internal`, `revert` and `automated` may omit the issue.
3. Keep the branch short-lived and the change small, 250 changed lines or fewer when you can. One concern per PR.
4. Open the PR with the title `<type>(<scope>): <summary> (GH-<n>)` and `Closes #<n>` in the description. The PR template lists the rest.
5. Merge with squash once CI is green. Delete the branch on merge.

## Commits

[Conventional Commits](https://www.conventionalcommits.org): `<type>(<scope>): <summary>`, imperative, lowercase, no trailing period. Scopes: `web`, `android`, `ml`, `data`, `ci`. The body explains why, not what the diff shows. Mark a breaking change with `!` or a `BREAKING CHANGE:` footer.

Releases are automatic: release-please reads the squashed commits on `main`, keeps a release PR with the changelog and the version (in `web/package.json` and the Android `versionName`), and merging that PR tags the release. The APK is then built and attached to the release as the README describes. Do not edit `CHANGELOG.md` by hand.

## Data and licences

- The model's training data stays out of the repository; only the scripts that download and prepare it are here.
- Reference photos shipped in the apps must carry a licence that allows redistribution (CC0, CC BY or CC BY-SA) and their credit (`shared/miniaturas2/creditos.json`). Never add a photo without one.
- Flower cards (`shared/flores.json`, `shared/fichas2/`) follow `shared/fichas2/ESPEC.md`.

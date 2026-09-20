# Pace Android v4

Pace is the Android version of the finance PWA with native SMS and notification transaction import.

## v4 changes

- Historical SMS import grouped by calendar date.
- Each date has its own **Select all** checkbox.
- A global **Select all messages** option is also available.
- Individual SMS messages can still be selected/deselected.
- Up to 3000 selected-sender SMS messages are grouped by date for review.
- Transaction parser preserves the original SMS transaction date when one is present.
- Fixed the Kotlin `Matcher.groups.getOrNull()` compilation error.
- `versionCode` increased to 4 and `versionName` to 4.0.0.
- GitHub Actions now uses a persistent signing key so later APKs can update an installed Pace APK.
- Live transaction notifications remain review-first: they are not automatically added.

## GitHub Actions signing setup

Read `SIGNING_SETUP.md` once and add the four repository secrets before running the workflow.

## Build

The workflow builds:
- `pace-debug-apk` -> signed `app-debug.apk`
- `pace-release-apk` -> signed `app-release.apk`

The signing key is never stored in the repository. GitHub Actions reconstructs it from repository secrets.

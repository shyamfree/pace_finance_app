# Pace Android v8

## Historical SMS import

The historical SMS importer is implemented as a single native three-step wizard rather than nested Android AlertDialogs:

1. **Date range** — choose From and To dates.
2. **Senders** — Pace queries the SMS inbox asynchronously and shows only senders that actually have inbox SMS in that range, with message counts.
3. **Messages** — Pace queries the selected senders and groups messages by their actual SMS calendar date. Each date has its own Select All checkbox, plus a global Select All.

The sender query and message query both run off the UI thread. The wizard displays explicit loading, no-results, and permission/query-error states instead of silently returning to the previous dialog.

Historical SMS transaction parsing uses the SMS timestamp as the fallback transaction date and keeps the extracted transaction date separately when present in the message.

## Signing

GitHub Actions uses the persistent signing secrets described in `SIGNING_SETUP.md`. Do not commit the keystore or its Base64 contents.

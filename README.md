# Pace Android v6

Pace is the Android finance app with native SMS and payment-notification transaction detection.

## Historical SMS import — v6

The historical import flow is now:

1. Choose a **From** date and **To** date.
2. Pace scans only SMS messages inside that date range.
3. Pace shows only **senders that have messages in that range**, with message counts.
4. Select one or more senders.
5. Pace groups the matching messages by their **actual SMS calendar date**.
6. Use **Select all** for an individual day, select individual messages, or select all messages.
7. Extract/import only the selected messages.

The SMS received timestamp and the transaction date extracted from the SMS are kept separate.

## Other behavior

- Live SMS/payment notifications stay pending until the user explicitly chooses Add or Ignore.
- Duplicate protection is applied to historical and live imports.
- Imported transactions remain in Pace even if the original SMS is later deleted.
- Imported transaction fields remain editable.
- Bank sender/rule configuration is available from Import settings.
- `versionCode` is 6 and `versionName` is 6.0.0.

## GitHub Actions signing

The workflow intentionally requires a persistent signing key. This is necessary for APK updates: Android will reject an update signed with a different key.

The workflow uses these repository secrets:

- `PACE_KEYSTORE_BASE64`
- `PACE_KEYSTORE_PASSWORD`
- `PACE_KEY_ALIAS`
- `PACE_KEY_PASSWORD`

See `SIGNING_SETUP.md` for the one-time setup.

Do not commit the `.jks` file or its base64 contents to the repository.

## Build artifacts

GitHub Actions produces:

- `pace-debug-apk`
- `pace-release-apk`

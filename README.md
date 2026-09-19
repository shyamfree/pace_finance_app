# Pace Android

Native Android build of Pace with bank/payment transaction import support.

## Features
- Existing Pace PWA UI embedded in a native WebView.
- Bank SMS transaction detection.
- Android NotificationListenerService for bank/UPI/payment notifications.
- Configurable sample-message rules with optional regex and sender/app keyword.
- Persistent native pending transaction store, so dismissing/removing a notification does not lose the detected transaction.
- Review notification with Add/Ignore actions.
- Editable amount, merchant/note, type and category before adding.
- Duplicate fingerprint protection.
- Local Pace storage remains on-device.

## Build
GitHub Actions workflow: `.github/workflows/android.yml`.
Run it manually from GitHub Actions or push to `main`/`master`.

The APK artifacts are produced under the Actions run.

## Android setup
After installing the APK:
1. Allow SMS permission if you want bank SMS detection.
2. Allow notification permission on Android 13+.
3. Open Pace > Settings > Bank & payment imports.
4. Enable Notification access for Pace if you want payment-app/bank notification detection.
5. Paste a sample bank message and optionally add a regex. Amount must be capture group 1 for a custom regex.

## Privacy
No transaction data is uploaded by this project. SMS/notification content is processed locally on the device and stored locally until reviewed/added/ignored.

# Pace Android

Pace is an Android finance app built around the existing Pace web UI with native Android transaction-import features.

## Included in v3

- Bank SMS transaction detection.
- Payment/bank notification detection using Android Notification Listener.
- Context-aware debit/credit classification. For example, `Acct debited ... recipient credited` is treated as an expense because the user's account was debited.
- Amount, merchant, transaction date, UPI/UTR/RRN reference and source extraction.
- Review notification before live transactions are added.
- Historical SMS import: choose multiple bank/payment sender IDs, select individual messages, parse them using their original dates, and import the selected transactions.
- Duplicate protection using a transaction fingerprint.
- Custom bank message rules with optional sample message and regex.
- Every imported transaction remains editable after it is added, including type, amount, date, category, merchant/note and UPI/reference.
- User-managed expense and income categories: add and rename categories.
- Daily spending limit setting.
- Daily spending line graph with the daily-limit reference line.
- Existing monthly income/expense and six-month trend views.
- Local device storage through the existing Pace data store.

## Android permissions

Pace requests SMS access for transaction detection and historical import, and notification access for payment/bank notification detection. These permissions are optional until the corresponding features are used, and Android displays the system permission/access UI.

## Build

GitHub Actions builds both a debug APK and an unsigned release APK. The workflow uses Android SDK 35, JDK 17 and Gradle 8.9.

The debug APK is published as the `pace-debug-apk` workflow artifact.

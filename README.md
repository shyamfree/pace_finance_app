# Pace Android v12

Pace is a local-first personal finance app.

## v12 scope

- Removed SMS inbox reading/import and screenshot/OCR transaction extraction.
- Keeps payment/bank-app notification capture only. Detected notifications remain pending until the user chooses Review/Add or Ignore.
- Review screen includes editable amount, Expense/Income type, category dropdown, date, time, merchant, note, and UPI/transaction reference.
- Opening Review cancels the source notification. Add and Ignore also remove the notification and pending item.
- Daily reminder with configurable time and note. The reminder notification includes today's recorded expense, income, highest spending category, and the saved note.
- Black/yellow high-contrast UI.
- Expense and income custom categories with add/rename support.
- Budget category limits include an Add expense category action.
- Category-based spending chart.
- Budget analyser showing where money is going, category share, limit usage, and categories that need attention.
- Time is stored and displayed for every transaction.

## Android permissions

Pace requests notification permission for transaction alerts and the daily reminder. It does not request SMS permissions.

## Build

GitHub Actions is the Android build environment. Keep the persistent signing secrets described in `SIGNING_SETUP.md` so future APKs can update the installed app.

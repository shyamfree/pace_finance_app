# Pace v9 build notes

This release fixes the Kotlin compile error in the SMS import wizard caused by referencing
`fromButton` and `toButton` from their own initializers. They are now declared with `lateinit`
and assigned before their click callbacks use them.

Historical SMS import is a 3-step wizard:
1. Date range
2. Sender list queried from SMS inbox in that range
3. Messages grouped by SMS date with per-day and global Select All

The GitHub Actions workflow remains the authoritative Android build environment.

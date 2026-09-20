# Pace APK updates — one-time signing setup

Pace now uses `versionCode 4` / `versionName 4.0.0` and a persistent signing key in GitHub Actions.

Why this is needed:
- Android only allows an update when the package signature matches the installed app and the versionCode increases.
- Older Pace builds used the default GitHub runner debug signing and also reused the same versionCode.
- This workflow uses one persistent keystore stored as GitHub Actions secrets.

## One-time setup from Termux

Run these commands in a safe directory, not inside the Git repository:

```bash
cd ~
mkdir -p pace-signing
cd ~/pace-signing
read -s -p "Enter a strong Pace signing password: " PACE_PASSWORD; echo
keytool -genkeypair -v -keystore pace-signing.jks -alias pace -keyalg RSA -keysize 2048 -validity 10000 -storepass "$PACE_PASSWORD" -keypass "$PACE_PASSWORD" -dname "CN=Pace Finance, OU=Pace, O=Pace, L=Local, ST=Local, C=IN"
base64 -w 0 pace-signing.jks > pace-signing.base64
printf '\nKeystore created: %s\n' "$PWD/pace-signing.jks"
printf 'Alias: pace\n'
printf 'Base64 file: %s\n' "$PWD/pace-signing.base64"
```

Keep the password private. Do not commit `pace-signing.jks` or `pace-signing.base64` to GitHub.

## Add four GitHub Actions secrets

Open the repository on GitHub:
`Settings -> Secrets and variables -> Actions -> New repository secret`

Create:

1. `PACE_KEYSTORE_BASE64`
   - Copy the entire one-line contents of `~/pace-signing/pace-signing.base64`
2. `PACE_KEYSTORE_PASSWORD`
   - The password you entered above
3. `PACE_KEY_ALIAS`
   - `pace`
4. `PACE_KEY_PASSWORD`
   - The same password you entered above

After these are added, every Pace APK from the workflow is signed with the same key.

## Important first update

If the currently installed Pace APK was signed with a different key, Android will not allow the first v4 update over it. In that case uninstall the old Pace APK once, install the new v4 APK, and then future Pace APK updates will install normally as long as the versionCode keeps increasing.

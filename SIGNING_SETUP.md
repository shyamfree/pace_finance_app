# Pace APK signing — one-time setup

The previous build failed because GitHub Actions could not find `PACE_KEYSTORE_BASE64`.
This is a signing configuration issue, not an Android/Kotlin compilation error.

Run this once in Termux:

```bash
cd ~
rm -rf pace-signing
mkdir -p ~/pace-signing
cd ~/pace-signing
read -s -p "Enter a strong Pace signing password: " PACE_PASSWORD; echo
keytool -genkeypair -v -keystore pace-signing.jks -alias pace -keyalg RSA -keysize 2048 -validity 10000 -storepass "$PACE_PASSWORD" -keypass "$PACE_PASSWORD" -dname "CN=Pace Finance, OU=Pace, O=Pace, L=Local, ST=Local, C=IN"
base64 -w 0 pace-signing.jks > pace-signing.base64
```

Do **not** put either file in the Git repository.

Then open:

GitHub → `shyamfree/pace_finance_app` → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

Create these four secrets:

| Secret | Value |
|---|---|
| `PACE_KEYSTORE_BASE64` | Entire contents of `~/pace-signing/pace-signing.base64` |
| `PACE_KEYSTORE_PASSWORD` | The password entered above |
| `PACE_KEY_ALIAS` | `pace` |
| `PACE_KEY_PASSWORD` | The same password |

After saving all four, rerun **Build Pace Android APK**.

## Important

The signing key must remain the same for future Pace APK updates. Keep a secure backup of `pace-signing.jks` and its password.

If an already-installed Pace APK was signed with a different key, the first APK signed with this new persistent key may require uninstalling the old APK once. After that, future builds can update it normally as long as `versionCode` keeps increasing.

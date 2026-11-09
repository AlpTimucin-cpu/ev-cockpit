# TomTom API Key Security

## Status (October 26, 2026)

**Earlier releases require review.** `build-ev-cockpit.yml` previously passed `secrets.EVCOCKPIT_TOMTOM_API_KEY` to the build, and `patch_mediaengine.py` could write it into `assets/evcockpit/traffic-config.js`. Since GitHub Actions artifacts are downloadable, the key may have been extractable from an older APK if that secret was configured.

**New approach:** The shared key is not supplied to the build environment. `patch_mediaengine.py` always writes an empty key to `traffic-config.js` and rejects the build if the legacy `EVCOCKPIT_TOMTOM_API_KEY` environment variable is populated. GitHub Actions validates the configuration file in the final APK.

## Encrypted key storage (October 26, 2026)

Storage using AES-256-GCM in Android Keystore was added based on the user-uploaded `EvCockpitApiKeyStore.java`. There is **no plaintext/Base64 fallback** on devices below Android 23; saving the key fails if secure storage is unavailable.

The existing native Android key-entry dialog in `EvCockpitNavigatorActivity` is retained. The legacy `evcockpit_traffic_keys/tomtom` preference is migrated **once** to encrypted storage when the application first needs the key, and the plaintext copy is deleted. If the Keystore key is reset or corrupted, the user must enter the key again.

The uploaded `EvCockpitKeyBridge.java` and `evcockpit-tomtom-key.js` were **deliberately not included** in this release. An `@JavascriptInterface` method such as `getTomTomKey()` could be accessible from every WebView frame, unnecessarily increasing exposure. Existing JavaScript using the TomTom layer receives the key from native Android code when the page loads; visibility of the key in traffic URLs remains a separate, ongoing risk. This improves **on-device storage security** only; it does not guarantee absolute secrecy in the browser runtime.

## Personal key in the application

1. The user opens **Map service key** in EV Cockpit.
2. They enter their own TomTom API key.
3. The key is held in Android's app-private `SharedPreferences` and passed to WebView at runtime when needed. It may be visible in browser resource requests; this is not complete concealment.
4. The key is never written to the GitHub repository, `BuildConfig`, or shared APK assets.

Every user of the distributed application should use **their own key**, rather than sharing one key with everyone. For a trusted multi-user deployment, a server proxy with rate limiting, authentication, and cost controls can be considered separately.

## Historic main-branch TomTom key handling (prior to integration)

The older Media Hub branch used `secrets.ID4_TOMTOM_API_KEY` and
`patch_mola.py` with `assets/id4/traffic-config.js`, rather than the
EV Cockpit-prefixed names above. The archived key handling is **not**
a validation that prior APKs were secret-free. Treat any potentially
embedded prior key as compromised and rotate it separately. The existing
native Release signing hold must remain in place until credential and
signing-certificate continuity are independently verified.

## Actions for earlier releases

- **Rotate / revoke** the old key in the TomTom Developer dashboard. ChatGPT cannot perform this directly.
- Review usage and quota/spending limits.
- Inspect previously published APKs and Actions artifacts. Treat an extractable old key as compromised.
- Scan Git history with a secret scanner and verify suspicious findings. If history cleanup is needed, use a separate backup and plan; do not immediately force-push.
- Enable secret scanning and push protection under Repository Settings → Code security if available for the account/plan.
- Keep general Actions permissions read-only and do not expose secrets to untrusted PR code through `pull_request_target`.

## APK package check

```bash
python3 - <<'PY'
from zipfile import ZipFile
from pathlib import Path

apk=next(Path('mediaengine/dist').glob('*.apk'))
with ZipFile(apk) as z:
    config=z.read('assets/evcockpit/traffic-config.js').decode('utf-8')
    assert config.strip() == 'window.EVCOCKPIT_TRAFFIC_CONFIG={tomtomKey:""};'
print('PASS: APK traffic-config.js has no embedded TomTom key')
PY
```

This test only verifies that the specified configuration file does not contain an embedded key. It does not prove that older APKs are safe or that the entire binary has been scanned. Review older builds separately.

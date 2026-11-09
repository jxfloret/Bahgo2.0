# BahGo privacy & third-party audit (2026-10-29)

Checked: the Android app source, its dependencies and network calls, and the Firebase Realtime
Database (read-only checks, no data changed). Not legal advice: have the documents reviewed
before publishing.

## 1. Does the app need a cookie consent banner?

**No.** BahGo is a native Android app with no WebView and no browser cookies. It does store
data on the phone (SharedPreferences, files, caches) and uses Firebase identifiers; these are
listed in the Cookie Policy (`app/src/main/assets/legal/cookie_policy.html`). Consent is still
needed for the **optional analytics**, which is handled by the consent form (below).

## 2. Is there tracking / analytics?

**Yes: Google Analytics for Firebase** (`firebase-analytics` in `app/build.gradle.kts`).
The code never calls it, but the SDK collects data automatically on install: app-instance ID,
device model, OS version, country, sessions, screen views, first open.

**Fixed:**
- Analytics is now **off by default** (`firebase_analytics_collection_enabled = false` in the
  manifest) and only turned on if the user opts in (sign-up form, consent screen, or
  Settings > Privacy & Security > Usage statistics).
- The advertising ID is never collected (`google_analytics_adid_collection_enabled = false`).
- Consent (version, time, analytics choice) is stored on the phone and in
  `/users/{phone}/consent`.

If you don't need analytics at all, simply remove `firebase-analytics` from the dependencies.

## 3. Are the third-party services safe?

| Service | What it gets | Verdict |
|---|---|---|
| Google Firebase (RTDB, Authentication, Cloud Functions, FCM, Analytics) | Account data, login records, FCM token; analytics only with consent | OK as a provider (see 4.1) |
| sms-gate.app (SMS Gateway for Android) | Phone number + OTP text (sent from the server) | OK as a provider (see 4.2) |
| OpenStreetMap Nominatim | Search text + IP | OK. Usage policy needs a real User-Agent (done), ≤1 request/second (debounced) and attribution, **added** to the Search screen |
| OpenFreeMap (map style/tiles) | IP + map area | OK (HTTPS, no account, no tracking). Map attribution is in the MapLibre "i" button |
| Hugging Face (Project NOAH PMTiles) | IP | OK (HTTPS, public dataset). Only used if the bundled file is missing |
| Open-Meteo | IP only (fixed Taguig coordinates) | OK (HTTPS, no key, no personal data). Attribution shown on Home |

No ads SDKs, no web embeds, no WebViews, all connections HTTPS.

## 4. Problems to fix before real users sign up

### 4.1 Account data was publicly readable: FIXED in code, needs deploying
Before: without signing in, anyone could read `/users` (every account's name, phone number,
password hash and salt) and `/logins` (every login's phone number and time), because the app
checked passwords by downloading the stored hash. This is a reportable personal-data exposure
under the Data Privacy Act (RA 10173).

Now: passwords are checked on the server (`functions/auth.js`), which signs the app in to
Firebase Authentication. `database.rules.json` lets each user read/change only their own
`/users/{phone}`; password hashes move to `/credentials` and SMS codes (stored hashed) to `/otp`,
both server-only; `/logins` is written by the server only. Failed logins are limited (5 tries,
then 15 minutes) and SMS codes expire after 5 minutes (3 tries, at most 5 per hour).

**Not live until deployed**: follow `docs/SECURE_LOGIN_DEPLOY.md` (functions and new app first,
then the rules). Still open: the sensor nodes can be written by anyone (see that file).

### 4.2 SMS gateway credentials were in the repository and in the app: FIXED in code
Codes are now sent by the server, which reads the login from Firebase secrets. The app no longer
contains it (BuildConfig fields removed). **Still to do**: change the SMS gateway password (the
old one is in every APK built so far and in git history), store the new one with
`firebase functions:secrets:set`, and delete the values from `gradle.properties`.

### 4.3 Background location is requested but not used
`ACCESS_BACKGROUND_LOCATION` is asked for ("Allow all the time"), but nothing uses location in
the background. Google Play requires a justification and users find it alarming. Fix: remove the
permission and the "Allow always" option.

### 4.4 Unused SDKs
`firebase-firestore` is included but unused; remove it (smaller app, less to disclose).
`firebase-auth` is now used for sign-in (4.1).

## 5. What was added

- Documents (in-app, `app/src/main/assets/legal/`): Privacy Policy, Terms and Conditions,
  Cookie Policy, Data Privacy Consent Form. Fill in `[DEVELOPER / TEAM NAME]`, `[ADDRESS]`
  and `[CONTACT EMAIL]`.
- Consent: required checkbox + optional analytics on the sign-up form; a consent screen for
  existing accounts (shown once per document version); Settings rows for the documents and the
  Usage statistics switch.
- Commitments in the Privacy Policy you must be able to keep: delete accounts within 30 days of
  a request, keep login records up to 12 months, respond to data requests at the contact email.

# Secure sign-in: how to deploy (2026-10-29, updated 2026-11-07)

**Update 2026-11-07: not used for now (the team stays off Cloud Functions).** The app keeps
its on-phone sign-in and signs in to Firebase anonymously (free); `database.rules.json` holds the
rules for that: the team's roles (`admin`/`super` read, `device` writes `/Sensordata1..4`) plus
anonymous app sign-ins, and `/users`, `/logins` open to signed-in apps only. This guide is the
later upgrade. If you do it, `functions/auth.js` already tags app sign-ins with role `user`, and
the rules become:

```json
{
  "rules": {
    ".read": false,
    ".write": false,
    "$node": {
      ".read": "$node.matches(/^Sensordata[1-4]$/) && (auth.token.role === 'admin' || auth.token.role === 'super' || auth.token.role === 'user')",
      ".write": "$node.matches(/^Sensordata[1-4]$/) && auth.token.role === 'device'"
    },
    "users": {
      "$uid": {
        ".read": "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid && newData.exists()",
        "phoneNumber": { ".validate": "newData.val() === data.val()" },
        "passwordHash": { ".validate": false },
        "salt": { ".validate": false },
        "createdAt": { ".validate": "newData.val() === data.val()" }
      }
    }
  }
}
```
The app's `auth/AuthApi.kt` must then be switched to call the endpoints below.

Sign-in, sign-up, SMS codes, password changes and phone-number changes now run on the server
(`functions/auth.js`). The app signs in to **Firebase Authentication** with the token the server
returns, and the database rules (`database.rules.json`) let each user read and change only their
own `/users/{phone}` entry. Password hashes (`/credentials`), SMS codes (`/otp`), failed-login
counters (`/loginAttempts`) and the login log (`/logins`) can only be read by the server.

**Order matters.** Deploy the functions and give out the new app *before* deploying the rules.
If the rules go first, the old app can't log in; if the new app goes first, it can't log in until
the functions are live.

## 1. One-time setup

1. Firebase project `bahgo-ee71b` must be on the **Blaze** (pay-as-you-go) plan. Functions that
   call outside services (the SMS gateway) and use secrets need it. Normal student use stays in
   the free allowance.
2. Install the CLI and log in:
   ```bash
   npm install -g firebase-tools
   ```
   ```bash
   firebase login
   ```
3. **Change the SMS gateway password first** (in the SMS Gateway for Android app / sms-gate.app
   account). The old one was in `gradle.properties` and in every APK built so far.
4. Store the new login as secrets (you'll be asked to type each value):
   ```bash
   firebase functions:secrets:set SMSGATEWAY_USERNAME --project bahgo-ee71b
   ```
   ```bash
   firebase functions:secrets:set SMSGATEWAY_PASSWORD --project bahgo-ee71b
   ```
   Then delete `SMSGATEWAY_USERNAME` / `SMSGATEWAY_PASSWORD` from `gradle.properties` (the app
   no longer uses them).
5. Let the functions create sign-in tokens. In Google Cloud Console → IAM, find the
   **Default compute service account** (`<project-number>-compute@developer.gserviceaccount.com`)
   and add the role **Service Account Token Creator**. Or from a terminal with `gcloud`:
   ```bash
   gcloud iam service-accounts add-iam-policy-binding PROJECT_NUMBER-compute@developer.gserviceaccount.com --member=serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com --role=roles/iam.serviceAccountTokenCreator --project bahgo-ee71b
   ```
   Also make sure the **IAM Service Account Credentials API** is enabled for the project.
6. In Firebase Console → Authentication, click **Get started** once (no sign-in provider needs
   to be turned on; custom tokens always work).

## 2. Deploy the functions

From the project folder:
```bash
cd functions && npm install && npm test
```
```bash
firebase deploy --only functions --project bahgo-ee71b
```
This deploys the flood alerts and the five new endpoints, all in `asia-southeast1`:
`sendOtp`, `signUp`, `login`, `changePassword`, `changePhone`
(`https://asia-southeast1-bahgo-ee71b.cloudfunctions.net/<name>`).

Quick check (should answer `{"error":"Incorrect number or password."}`):
```bash
curl -s -X POST -H "Content-Type: application/json" -d "{\"phone\":\"09000000000\",\"password\":\"x\"}" https://asia-southeast1-bahgo-ee71b.cloudfunctions.net/login
```

## 3. Install the new app

Build and install the new app on every test phone, then log in once. Phones that were logged in
with the old app are sent to the login screen (they have no Firebase sign-in yet).

Existing accounts keep working: on their first login the server checks the old password hash
and moves it out of `/users` into `/credentials`.

## 4. Lock the database

Only after steps 2 and 3:
```bash
firebase deploy --only database --project bahgo-ee71b
```
Check in Firebase Console → Realtime Database → Rules that the new rules are shown. Then confirm
the old public read is gone (should answer `{"error" : "Permission denied"}`):
```bash
curl -s "https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/users.json"
```
The sensor data must still be readable (should print numbers):
```bash
curl -s "https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/Sensordata1.json"
```

If anything breaks, go back to the previous rules in Firebase Console → Rules → history.

## 5. Still open

- Sensor writes are limited to the role `device` (set up by the team for the boards).
- Old hashes of accounts that never log in again stay in `/users/{phone}` (now private to that
  user). They can be moved with a one-off script if needed.

## What's where

| Path | Who can read | Who can write |
|---|---|---|
| `/Sensordata1..4` | signed-in accounts with role `admin`, `super` or `user` (the app) | role `device` only |
| `/Last_Updated`, `/RecordedData` | server only (the app ships the recorded days inside itself) | server only |
| `/users/{phone}` | that signed-in user | that user (not the phone number or creation date) |
| `/credentials`, `/otp`, `/loginAttempts`, `/logins`, `/alertState` | server only | server only |

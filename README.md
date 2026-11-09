# Payment Bridge

bKash / Nagad / Rocket payment SMS + notification → **your own Supabase** (automatic, no need to open the app).

Every user uses **their own** Supabase URL + secret (App → SETTINGS). Nothing is hard-coded.

---

## 1) Supabase setup (once)

1. Supabase → **SQL Editor** → paste `supabase/schema.sql` → Run.
2. Install Supabase CLI, then:
   ```
   supabase login
   supabase link --project-ref YOUR_PROJECT_REF
   supabase secrets set BRIDGE_SECRET=put-a-long-random-text-here
   supabase functions deploy payment-bridge --no-verify-jwt
   ```
   (`--no-verify-jwt` is required.)
3. Your **Backend URL** = `https://YOUR_PROJECT_REF.supabase.co/functions/v1/payment-bridge`
   Your **Secret** = the same text you used for `BRIDGE_SECRET`.

(You can also keep using your own existing function — the app sends JSON
`sender_phone, amount, method, trx_id, message` with header `x-bridge-secret`.)

## 2) Build the APK

### A) GitHub (no PC tools)
Upload this folder to a GitHub repo → **Actions → Build APK → Run workflow** → download artifact `PaymentBridge-APK`.

### B) Android Studio
File → Open → select this folder → wait for Gradle sync → **Build → Build APK(s)**
(`app/build/outputs/apk/debug/app-debug.apk`).
JDK 17 is used (Android Studio Hedgehog / Iguana / newer has it built in).

Both ways use the same `app/debug.keystore`, so a new APK installs **over** the old one (no uninstall).

## 3) First run on the phone (important — 2 minutes)

Open the app → **KEEP-ALIVE SETUP**. Do all items:

1. Allow SMS permission
2. Allow Notification permission
3. Android 13+: App info → ⋮ → **Allow restricted settings**
4. Notification access → ON for Payment Bridge
5. Battery → **Unrestricted / Don't optimize**
6. Auto-start / Background activity → ON (Infinix/Tecno: Phone Master → Auto-start; Xiaomi: Autostart; Oppo/Vivo: Startup manager)
7. SETTINGS → Backend URL + Secret → TEST CONNECTION = CONNECTED ✓

Also lock the app in *Recent apps* (🔒). After this you can turn the screen off, close the app, open other apps — payments still go to Supabase.

## How it stays alive

- Foreground service (`specialUse`) + Notification Listener + SMS receiver.
- SMS receiver finishes the upload with `goAsync()` + a short wake lock (no more "open app for 1 second").
- Payments are saved in a local queue first, then sent. If internet is down they are retried every 15 s and instantly when the network returns.
- Watchdog alarm every 5 min re-starts the service if the phone killed it.
- Duplicate TrxIDs are ignored (SMS + notification of the same payment = sent once).

## Permissions used

`RECEIVE_SMS`, `READ_SMS`, `POST_NOTIFICATIONS`, notification-listener access, `FOREGROUND_SERVICE(_SPECIAL_USE)`,
`RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `INTERNET`.

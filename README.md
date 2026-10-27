# Trotinete: open app for the Xiaomi Electric Scooter 5

**English** · [Português](README.pt.md)

An **unofficial** Android app to control the Xiaomi Electric Scooter 5 without Mi Home. It adds a feature the Xiaomi app doesn't have: **automatic locking when you walk away**.

<p align="center">
  <img src="docs/painel.png" width="270" alt="Main dashboard">
  &nbsp;&nbsp;
  <img src="docs/detalhes.png" width="270" alt="Battery and scooter details">
</p>

**[⬇️ Download the APK (Releases)](../../releases/latest)** · Android 10 or newer · *The app's interface is in Portuguese (European).*

---

## Features

| | |
|---|---|
| 🔋 **Dashboard** | Battery, range, live power, voltage, trip and total km |
| 🔒 **Lock / unlock** | One tap |
| 📡 **Proximity auto-lock** | Locks by itself when your phone walks away and unlocks when you come back (only if the app locked it) |
| 🛡️ **Safety** | **Never locks while riding**: before locking, it re-checks on the scooter itself that it is stopped and drawing little power |
| 💡 **Tail light, modes, Find** | Tail light always on, Walk/Drive/Sport, beep to find it |
| ⚙️ **Riding settings** | Regenerative braking, cruise control, traction control and other options the scooter accepts |
| 🗺️ **Trips** | Automatic GPS recording: route, distance, energy used and cost in €, plus **consumption per mode** (Wh/km and estimated range in Drive vs Sport) |
| 🚦 **Speed alert** | The phone vibrates and beeps above a speed you choose (GPS), handy in 20 km/h zones |
| 🌱 **Automatic eco mode** | With low battery, switches from Sport to Drive so you get home, always at the next stop, never while riding |
| 🔔 **Battery alerts** | For example "reached 90%": stopping the charge at 80–90% makes the battery last more years |
| 🩺 **Battery health** | Charge cycles, health and temperatures, plus a history over time |

### What it does **not** do (on purpose)

- **It doesn't touch speed limits.** The 25 km/h limit is a legal and safety limit. Those properties are blocked in the code.
- **It doesn't turn on the front light.** The Scooter 5 only accepts that from the physical button, not over Bluetooth.
- **It doesn't install firmware.** Xiaomi's firmware is signed. The app doesn't go near it.

---

## Before installing

1. **The scooter must be paired in Mi Home** (your Xiaomi account). Xiaomi creates the scooter's Bluetooth key when you pair it, and there is no other way to get that key.
2. **Don't remove the scooter from Mi Home.** If you pair it again, the key changes and you have to set up the app again.
3. The app only needs to fetch the key **once**. After that it works without internet and without Mi Home.

## Install

1. Download `trotinete-x.y.z.apk` from [Releases](../../releases/latest).
2. Open the file on your phone. Android will ask you to allow installing from "unknown sources": allow it just for this file.
3. Open the app and tap **Entrar com a conta Xiaomi** ("Sign in with your Xiaomi account"):
   - Xiaomi's official login page opens **on top of the app** (a browser tab, like "Sign in with Google"), with no links to copy.
   - When you sign in, you're brought back to the app and it finds the scooter in your account. If you have several, it asks which one.
   - If your scooter has a PIN, the app asks for it.
   - The app only asks you to scan the QR sticker if Xiaomi doesn't return the scooter's Bluetooth address.
4. Done: the app connects to the scooter by itself whenever it's nearby.

> **Auto-lock:** for it to work with the screen off, accept the prompt to **exclude the app from battery optimisation**. Otherwise Android may stop it in the background.

## Tuning the proximity auto-lock

In **Controlo → Segurança** (Controls → Safety):

- **Threshold (dBm):** the further left, the further away you have to go before it locks. The default is **−78 dBm**, tuned in a real home: next to the scooter the signal is −62 to −75, and at 15–20 m it drops to −83 to −89.
- **Seconds:** how long the signal must stay weak before locking. A sudden drop in signal (for example when you close the door) locks it right away.
- **Unlock when I come back:** unlocks when the signal stays above threshold + 10 dBm for 2 s, and only if the app was the one that locked it. The app never undoes a lock you made by hand.

The screen shows the **current signal**. Walk away slowly and watch the numbers to pick your own threshold.

⚠️ Bluetooth signal varies with walls, pockets and your body. This is a convenience and **not a replacement for a real lock**.

## Privacy

- The scooter's key is stored **encrypted with Android Keystore** on your phone.
- The internet is only used for the Xiaomi login, to fetch the key. No telemetry, no ads, no servers of ours.
- The login is Xiaomi's official page (`account.xiaomi.com`), shown by your browser: the app never sees your password.
- Trips (GPS) stay on your phone.

## Tested models

| Model | Status |
|---|---|
| Xiaomi Electric Scooter 5 (`xiaomi.scooter.0005`, BLE 2.7.0_0015) | ✅ Tested on a real scooter |
| Scooter 5 Pro / 5 Max | ❓ Same protocol, not tested. Please report in an *issue*. |
| Older models (Pro 2, 3, 4…) | ❌ They use a different protocol |

Still to confirm on a real ride: cruise control, ASR, hill descent assist and hill hold are in the app but haven't been checked with the scooter moving.

---

## For developers

```
android/   Android app (Kotlin + Jetpack Compose)
  protocolo/   Xiaomi MIOT over Bluetooth: ECDH login + AES-CCM, property get/set
  servico/     Foreground service: connection, auto-lock, trip recording
  dados/       Xiaomi account (key), settings, battery log
  ui/          Compose screens
pc/        Python tools (bleak) to study the scooter from a PC
```

**Build the app:**

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

The debug build accepts commands over adb, which makes studying the protocol easier:

```bash
adb shell am broadcast -n dev.josefelipe.trotinete/.servico.Depuracao --es cmd "get 2 4"
adb logcat Trotinete:* '*:S'
```

**PC tools** (Linux/macOS/Windows with Bluetooth, [uv](https://docs.astral.sh/uv/)):

```bash
cd pc
uv run chave.py            # fetches the key from your Xiaomi account (QR in Mi Home)
uv run ler.py AA:BB:CC:DD:EE:FF
```

The key is saved in `pc/secrets/`, which git ignores.

The code and comments are in Portuguese.

## Credits

- The Scooter 5 Pro Bluetooth protocol was documented by [KuziaMother/SCOOTER_5_PRO](https://github.com/KuziaMother/SCOOTER_5_PRO) and [desperado0044/xiaomi-scooter-5pro-ble](https://github.com/desperado0044/xiaomi-scooter-5pro-ble). This project is an independent implementation based on their documentation.
- The Xiaomi account login is based on [Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor) by Piotr Machowski (MIT).

## Disclaimer

A personal project, **not affiliated with Xiaomi in any way**. "Xiaomi" and "Mi Home" are trademarks of their respective owners. Use at your own risk: the author is not responsible for damage, fines, or scooters locked in the wrong place. Follow your country's traffic rules.

## License

[MIT](LICENSE)

# Deckline – a music player for Android

An original music player with a glowing hi-fi style display, a spectrum visualizer,
a system equalizer, your whole music library with search, shuffle/repeat, and
background playback with lock-screen and headphone controls.

## Get the APK without installing anything (GitHub builds it for you)

1. Create a free account at https://github.com and click **New repository**.
   Name it `deckline`, choose **Private**, and click **Create repository**.
2. On the new repository page click **uploading an existing file**.
   Unzip `Deckline.zip` on your computer, open the `Deckline` folder, select
   **everything inside it** (including the `.github` folder) and drag it into the page.
   Click **Commit changes**.
3. Open the **Actions** tab. A build named "Build APK" starts on its own and takes
   about 5 minutes. When it shows a green tick, it's done.
4. On your phone, open your repository on github.com (signed in), tap **Releases**
   → **Deckline – latest build** → **Deckline.apk**.
5. Open the downloaded file. Android will ask you to allow installs from your
   browser or Files app the first time: allow it, then tap **Install**.
6. Open Deckline and tap **Allow** so it can find the music on your phone.

**If the Actions tab says there are no workflows:** your computer hid the `.github`
folder during upload (common on Mac). In the repository click **Add file → Create new
file**, type `.github/workflows/build.yml` as the name, paste in the contents of that
file from the zip, and click **Commit changes**.

**To change the app later:** edit any file on GitHub and commit. A new APK is built
automatically, and it installs over the old one, keeping your settings.

## Building with Android Studio instead

Open this folder in Android Studio (Hedgehog or newer), let it sync, then use
**Build → Build APK(s)**, or plug in your phone and press **Run**.

## Notes

- Works on Android 7.0 and newer.
- The signing key (`app/deckline.keystore`, password `deckline`) is included so
  every build can update the previous one. That's fine for a personal app; keep the
  repository private, and create your own key before ever publishing to the Play Store.
- The equalizer uses your phone's built-in audio effects, so the number of bands
  and the presets depend on the phone model.

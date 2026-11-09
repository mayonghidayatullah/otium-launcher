# Otium Launcher

<img src="docs/screenshot.png" width="720">

Apple TV style home launcher for Android TV, tuned for low-RAM sets (tested on Xiaomi TV A32, 1.5 GB RAM, Android 11).

Based on the original [tv-home](https://github.com/mihirgupta0900/tv-home) by Mihir Gupta (MIT). Modifications by Mayong Hidayatullah.

## Features

- Indonesian / English UI (Auto, Indonesia, English)
- Settings screen: display toggles (greeting, clock, date, weather), greeting name, weather city
- Weather widget (Open-Meteo, no API key, refreshed every 30 minutes)
- Photo wallpapers with slideshow (off, 30 s, 60 s, 5 min, 10 min)
- Video wallpapers (MP4) that rotate on the same interval, without taking audio focus from other apps
- Dock editor: move, remove, add apps, reset
- Sources menu inside Settings
- Nunito SemiBold font, full-color wallpaper decoding

## Your own wallpapers

Copy JPG/PNG/WebP files to:

    /sdcard/Android/data/com.mayong.otium/files/wallpapers/

Copy MP4 files (720p, H.264, no audio recommended) to:

    /sdcard/Android/data/com.mayong.otium/files/videos/

Example: `adb push photo.jpg /sdcard/Android/data/com.mayong.otium/files/wallpapers/`

Then pick videos in Settings > Choose video.

## Install

    adb install -r tvhome.apk
    adb shell cmd package set-home-activity --user 0 com.mayong.otium/.MainActivity

The package name is `com.mayong.otium`.

## Build on macOS

Run `./setup.sh` once, then `cd launcher && ./build-and-install.sh`.

## Build on Termux (no PC needed)

    pkg install openjdk-17 aapt2 apksigner d8 zip
    # put android.jar (platform-30) at ~/sdk/android-11/android.jar
    cd launcher && ./build-termux.sh

## Privacy

The launcher uses the internet only to fetch weather and search cities from Open-Meteo.

## Credits

- Original launcher: [Mihir Gupta](https://github.com/mihirgupta0900/tv-home) (MIT License)
- Font: Nunito by The Nunito Project Authors (SIL OFL 1.1, see launcher/assets/fonts/OFL-Nunito.txt)
- Bundled wallpapers: photos by Daniel Sessler and Sylwia Bartyzel on Unsplash
- Bundled video wallpapers (air-terjun.mp4, alpen-salju.mp4): Mixkit (mixkit.co), used under the Mixkit Free License. Included for use inside the app only, do not redistribute them as separate files.
- Weather data: Open-Meteo

## Disclaimer

Not affiliated with Xiaomi, Google, or any streaming service. Brand names belong to their owners.

## License

MIT. See LICENSE.

## Acknowledgments
Built with the help of Claude (Anthropic), which assisted with code and build scripts.

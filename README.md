# MyKey

A private Android keyboard. It never sends what you type anywhere, it has no app icon, and its only
permissions are vibration and internet for the optional online GIF search (off by default). Settings open from the keyboard
(long-press the globe key) or from system Settings → Keyboards → MyKey.

## Install (no computer needed)

Needs Android 11 or newer.

1. On your phone, open the [latest release](https://github.com/ItzAditya43/MyKey/releases/latest) and download **MyKey-vX.Y.Z.apk**.
2. Open the downloaded file. If Android asks, allow your browser or Files app to install apps.
   Play Protect may say it doesn't recognise the app; that's normal for apps not from the Play Store.
3. MyKey has no app icon. Turn it on in **Settings → System (or Additional settings) → Languages & input →
   On-screen keyboard (or Manage keyboards) → MyKey**. Android shows the same warning for every keyboard.
4. Open any text box, tap the keyboard switcher (bottom corner of the screen) and choose **MyKey**.
5. Open MyKey's settings from the gear in its toolbar, or long-press the globe key.

## Build from source

```fish
set -x JAVA_HOME ~/.local/jdk/jdk-21.0.13+9   # JDK 17–21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests: `./gradlew testDebugUnitTest` (logic) and the on-device suite in `app/src/androidTest`
(install the test APK and run it with `adb shell am instrument`; the default `connectedAndroidTest`
task uninstalls the app afterwards, which removes your keyboard and its data).

## Features

Every feature has its own switch in Settings.

- **Languages**: English (QWERTY, QWERTZ, AZERTY, Dvorak, Colemak), Spanish, Russian, Japanese
  (12-key flick or romaji, kana → kanji with Mozc data), Mandarin (pinyin → hanzi with AOSP PinyinIME
  data). Switch with the globe key or swipe up on space
- **Typing**: glide typing with a trail, key preview, long-press popups, space-bar cursor, swipe-delete,
  accelerating backspace, split layout (auto in landscape), field-aware layouts, action enter key,
  swipe gestures, keys that learn your aim, adjustable long-press/glide/backspace timing, space-bar trackpad,
  shift cycles a word's case, Restore after swipe-delete, hold backspace to delete words, Morse code layout
- **Correction**: proximity-aware autocorrect with accent fixing, undo with backspace, smart punctuation
  and curly quotes, smart shortcuts ({date} {time} {cursor}), emoji suggestions, calculator and unit
  conversion chips, auto-capitals, double-space period, system-wide spell checker
- **Learning**: learned words, next-word prediction, per-app habits, kanji choices, forget by long-press,
  incognito, typing stats. All in on-device SQLite
- **Tools**: quick toolbar, emoji panel (every emoji with skin tones, 9 symbol sets, kaomoji, search,
  recents), your own offline GIFs and stickers, snippets, paste and one-time-code chips, clipboard history with pinning and
  auto-clear, editing panel (select, copy, cut, paste, undo, redo), voice typing hand-off,
  one-handed and floating modes
- **Look**: 15 themes including true-black OLED ones (Crimson Noir, Abyss, Eclipse…), day/night and per-app themes,
  gradient and animated aurora backgrounds, sound packs, glide trail styles, theme editor with JSON import/export, background photo,
  key styles (filled, outlined, borderless), press animations (pop, ripple, glow, fade), letter fonts,
  overall size, key size/gap/roundness, vibration strength, key sound volume
- **Smart chips**: selected-text actions, password-manager suggestions, remembered email addresses, password
  generator, date suggestions, Restore
- **App profiles**: per-app theme, split, position, size, autocorrect and suggestions (good for games)
- **Privacy**: never sends what you type; private on the lock screen; internet only for opt-in GIF search
- **Backup**: export and restore everything as one JSON file

## Layout files (JSON)

Built-in layouts are in `app/src/main/assets/layouts/`. You can edit any of them in Settings →
*Edit layouts*; the edited copy is saved on the phone and used instead of the built-in one.

```json
{
  "name": "My layout",
  "long": { "e": "éè3", "a": "@áà" },
  "rows": [
    ["q","w","e","r","t","y","u","i","o","p"],
    ["a","s","d","f","g","h","j","k","l"],
    [{"type":"shift"}, "z","x","c","v","b","n","m", {"type":"backspace"}],
    [{"type":"symbols"}, {"type":"lang"}, {"label":"😂","out":"😂"}, {"type":"space"},
     {"label":".com","out":".com","w":1.4}, {"type":"enter"}]
  ]
}
```

A key is either a plain string (types that character, Shift capitalises letters) or an object:

| Field   | Meaning |
|---------|---------|
| `label` | What the key shows. Any text or emoji. |
| `out`   | What it types. Can be a whole word or phrase. |
| `long`  | Long-press options: a string (`"éèê"`, one option per character) or a list (`["Thanks!", "Ty"]`). |
| `w`     | Width. 1 is a normal key. |
| `flick` | Japanese flick key: centre, left, up, right, down (`"あいうえお"`). |
| `type`  | `shift` `backspace` `space` `enter` `symbols` `lang` `emoji` `clipboard` `voice` `settings` `kana_mod` `cursor_left` `cursor_right` |

The top-level `long` gives long-press options to plain string keys. Rows that contain special keys
stretch to the full width; rows of only letters are centred.

## Themes

`app/src/main/assets/themes.json`. Each theme sets `bg`, `key`, `mod` (special keys), `fg` (text),
`acc` (enter key), `accFg`, and optionally `border` (outline colour) and `amoled: true`.

## Word lists

`app/src/main/assets/dict/<lang>.txt`, one `word count` per line, from
[FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles 2018, CC BY-SA 4.0).

- Japanese: [Mozc](https://github.com/google/mozc) OSS dictionary (BSD-3, IPAdic licence), trimmed to the most common 260k entries
- Mandarin: AOSP PinyinIME `rawdict_utf16_65105_freq.txt` (Apache 2.0)
- Emoji: Unicode `emoji-test.txt`
- Fonts: Bricolage Grotesque and Figtree (SIL Open Font License)

# Reality NAS Player

**A personal cinema built for PICO Space Pro.**

**English** · [简体中文](docs/languages/zh-Hans.md) · [日本語](docs/languages/ja.md) · [Español](docs/languages/es.md) · [Deutsch](docs/languages/de.md) · [Français](docs/languages/fr.md)

<details>
<summary>All languages (23)</summary>

**English** · [简体中文](docs/languages/zh-Hans.md) · [日本語](docs/languages/ja.md) · [Español](docs/languages/es.md) · [Deutsch](docs/languages/de.md) · [Français](docs/languages/fr.md) · [Čeština](docs/languages/cs.md) · [Dansk](docs/languages/da.md) · [Ελληνικά](docs/languages/el.md) · [Suomi](docs/languages/fi.md) · [Italiano](docs/languages/it.md) · [한국어](docs/languages/ko.md) · [Bahasa Melayu](docs/languages/ms.md) · [Norsk bokmål](docs/languages/nb.md) · [Nederlands](docs/languages/nl.md) · [Polski](docs/languages/pl.md) · [Português (Brasil)](docs/languages/pt-BR.md) · [Română](docs/languages/ro.md) · [Русский](docs/languages/ru.md) · [Svenska](docs/languages/sv.md) · [ไทย](docs/languages/th.md) · [Türkçe](docs/languages/tr.md) · [繁體中文](docs/languages/zh-Hant.md)

[All languages (23) →](docs/languages/README.md)

</details>

![A closer view of Polar Letter playing on the Space Lounge screen, with Earth visible through the windows.](docs/showcase/00-screening-room-en.webp)

Open the movie and TV folders on your NAS. Reality NAS Player identifies the titles and builds a library you can browse by cover on **PICO Space Pro**. Pick a film, take a seat above Earth and start watching. The files stay on your drive.

**[Get the v1.0 headset app](https://github.com/jason-jm/reality-nas-player/releases/download/v1.0/reality-nas-player-1.0-4.apk)** · [Release notes and checksums](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) · [Desktop builds](docs/build.md)

## Turn NAS folders into a film library

![The files for Polar Letter on a WebDAV share and the first four covers on the headset after the scan.](docs/showcase/01-auto-scraping-en.webp)

Add a shared folder under **Sources** and start a scan. The player reads filenames, years and episode numbers to identify what you own. Its metadata scraper can turn a name like `Polar.Letter.2026.720p.mp4` into a film entry with a synopsis, credits and artwork. With automatic library updates enabled, later scans pick up new files.

Already organize your NAS with Kodi or tinyMediaManager? The player reads movie or series IDs in **NFO files** and uses posters and backdrops stored beside your videos. The example above was matched from a WebDAV share with online lookup switched off. For online film and TV matches, add your own TMDB API key. Anime matching can also use AniList and Bangumi. If the player picks the wrong title, correct it once and the choice survives an ordinary rescan.

## Browse your collection by its artwork

![The PICO Space Pro library shows eight film covers in two rows of four. Library categories are on the left, with Filter and sort controls above the covers.](docs/showcase/01-library-en.webp)

Open your library and browse the **poster wall**. Pick a category on the left, or use **Filter** to narrow the wall by genre, decade or watch status. Select a cover for the film’s synopsis and cast, or open a series to choose a season and episode. **Continue watching** takes you back to the scene you left.

## Bring the drive, share or server you use today

![The English Add a source screen with network-share and media-server options.](docs/showcase/02-connect-en.webp)

| Where your film lives | How to open it |
| --- | --- |
| On the headset | Open a video in the system file manager, or scan local storage under **Sources**. |
| On a computer or NAS | Choose **SMB** for a shared folder. WebDAV, NFS, FTP and SFTP are also supported. |
| In a home media server | Connect to **Jellyfin, Emby or Plex**, or browse a DLNA source on your network. |

A compatible phone app can send a video over DLNA, too. Turn on the headset’s cast receiver when you want to use it; it starts off.

## Pick a seat, not just a screen size

![In-app views of the Space Lounge, Private Cinema, Village Square and Desert Camp.](docs/showcase/03-cinemas-en.webp)

The **Space Lounge** has five seats. Choose the center for a straight view of the screen, or move to the window to watch with Earth in the corner of your eye. For a different evening, try the wood-lined **Private Cinema**, the open-air **Village Square** or the **Desert Camp** after dusk. The cinema and square have three seats each, and the rooms remember where you sat.

The film and its subtitles appear on the room’s screen. Set the house lights for the show and they come up when you pause. Prefer to keep an eye on the room around you? Switch to a floating window from the playback controls.

## Watch what was behind the camera

![A lakeside 360-degree demo viewed from two directions in the headset player.](docs/showcase/04-panorama-en.webp)

A 360° clip lets you turn away from the sunset and see the opposite shore. **180° and VR180** recordings cover the view ahead. In a floating window, drag to look around instead. The player checks the file for projection information and offers a manual override.

A stereo film is a different experience: **side-by-side and top-and-bottom 3D** give each eye its own view on the headset. An ordinary 2D film still plays on a flat screen.

## Keep the subtitles and soundtrack that came with the film

![Polar Letter playing with English captions, the track list and subtitle styling controls.](docs/showcase/05-playback-en.webp)

Keep the typesetting in **ASS/SSA** subtitles or select the **PGS and VobSub** picture subtitles from a disc. Choose embedded or external tracks; adjust subtitle size, position and timing where the format allows. An external dub can sit next to the original soundtrack. If Bluetooth dialogue arrives late, adjust the audio delay without leaving the film.

MKV and MP4 sit alongside older AVI, WMV, RMVB and Hi10P files. The player can decode DTS and TrueHD audio and find the main feature in an unencrypted Blu-ray or DVD backup (ISO, BDMV or VIDEO_TS). Interactive disc menus are not included. 4K and HDR playback depend on the particular encode and device.

## A film for the flight home

![The English Sync settings page for saving watch progress on a share you control.](docs/showcase/06-offline-en.webp)

Download a film to the headset before you leave Wi-Fi. Supported sources can resume an interrupted transfer. At home, two headsets can use an **SMB share you choose** to sync progress, favorites and your title corrections. Sync is optional; add the media sources again on a new headset.

There are no ads or analytics. Your library and server settings live on the device. Online metadata lookup sends search information such as a title to the relevant service; choose offline identification to use local file information instead. [Read the privacy policy](docs/privacy-policy.md).

## A desk is a good seat, too

![The desktop app showing six of the demonstration titles in its native window.](docs/showcase/07-desktop-en.webp)

The macOS and Windows app shares the headset’s library and metadata tools. Browse a collection, open an episode or play a local file with a mouse and keyboard. The spatial rooms belong to the headset experience. **v1.0 ships a headset APK; the desktop app is available to build from source.** [Desktop instructions](docs/build.md)

## Start with one film

1. Download the signed APK from [Releases](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) and sideload it for **PICO Space Pro / PICO OS 6**.
2. Open one of your own videos from Files, or add a shared folder under **Sources**. No NAS is required to try local playback.
3. Start the film and open **Viewing space** to choose your window or room.

The headset screens pictured here were captured in the PICO OS 6 emulator. The films and artwork in the demo library were created for this guide. [About the images](docs/showcase/README.md) · [Language directory](docs/languages/README.md) · [PICO Space Pro platform](https://developer.picoxr.com/document/discover/xr-devices/) · [MIT license](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)

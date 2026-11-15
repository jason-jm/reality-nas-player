# Reality NAS Player

[简体中文](README.md) · **English**

![Reality NAS Player playing a film on the Space Lounge screen with Earth outside the windows.](docs/showcase/00-screening-room-en.webp)

**Give the films you already own a cinema of their own.** Leave your files on your NAS, computer or headset. Put on your PICO, pick a poster, take the window seat above Earth and pick up where you left off. No player account required.

**[Download v1.0 for PICO (signed APK)](https://github.com/jason-jm/reality-nas-player/releases/download/v1.0/reality-nas-player-1.0-4.apk)** · [Release notes and checksums](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) · [macOS / Windows source and build guide](docs/build.md)

## Pick a poster, not a filename

![The real poster wall and film details, populated with original demonstration titles.](docs/showcase/01-library-en.webp)

Recognise the artwork but forget the title? Browse films, shows and anime by cover. Open a card for the synopsis and available cast, browse a series by season and episode, or use **Continue watching** to return to an unfinished film. Filter by watch status, genre or decade when you want something unwatched. You can correct a match when two films share a name.

Keep your existing Kodi-style `.nfo` files, posters and backdrops. To fetch film details from TMDB, enter your own API key under **Settings → Scraping**; anime metadata can also come from AniList and Bangumi. Playing a local file needs no key.

## Play from the library you already have

![PICO’s media sources screen lists file shares and dedicated media server connections.](docs/showcase/02-connect-en.webp)

Already sharing a folder from your computer? Choose **SMB** under Sources. A NAS can also connect over **WebDAV, NFS, FTP or SFTP**. Discover home media devices with **DLNA**, or connect to a **Jellyfin, Emby or Plex** library using its dedicated entry.

For a single local video, use the headset’s file manager and open it with Reality NAS Player. To send a video from a compatible phone app, enable the optional **DLNA cast receiver** and connect both devices to the same local network.

## Choose a room. Keep your seat.

![The Space Lounge seat picker together with in-app views of the private cinema, village square and desert camp.](docs/showcase/03-cinemas-en.webp)

Try a late show in the wood-panelled **Private Cinema**, an outdoor screening in the **Village Square** or a quiet night at the **Desert Camp**. The **Space Lounge** has five seats: sit in the middle for a straight view of the screen or take a window seat beside Earth. The private cinema and village square have three seats each. Each room remembers your choice.

Put the film and subtitles on the room’s screen, set the lights for playback and see them come back up when you pause. Switch to a floating window whenever you want the room around you back.

## Look around a panorama, see depth in a 3D film

![An original lakeside panorama shown in the PICO player with actual viewing controls.](docs/showcase/04-panorama-en.webp)

Turn around inside a **360°** landscape or look into a **180° / VR180** scene. In a window you can drag to look around instead. The player reads projection information when available and lets you correct it manually.

**Side-by-side (SBS) and top-and-bottom (TAB) 3D** films can send a separate image to each eye on the headset. Ordinary films still play on a flat screen.

## Read every line, hear the track you want

![A demo film playing with the actual subtitle track and styling controls.](docs/showcase/05-playback-en.webp)

Keep the styling of **ASS/SSA** subtitles, switch between embedded and external subtitles, and adjust their size, position and timing. Blu-ray PGS and DVD VobSub picture subtitles are supported too. Switch from the original soundtrack to a matching external dub; if Bluetooth audio falls behind, adjust the audio delay while watching.

Play everyday MKV and MP4 files alongside older RMVB, WMV, AVI and Hi10P material. The player can decode DTS and TrueHD audio and play the main feature from unencrypted Blu-ray or DVD backups (ISO, BDMV and VIDEO_TS). 4K and HDR results depend on the encoding and headset. Interactive disc menus are not supported in v1.0.

## Take a film offline. Bring your place home.

![The headset’s sync settings, where you choose whether to keep watch state on your own NAS.](docs/showcase/06-offline-en.webp)

Download a film to your headset before travelling; supported sources can resume an interrupted download. If you use two headsets, an optional **SMB share** can hold your watch progress, favourites and manual matches. Sync is off by default. Add your media sources again on a new device to use the same watch state.

No player account, ads or analytics. Your library and server settings stay on your device. Online matching sends search information such as a title to the selected metadata services; offline identification uses local file information instead. The interface is available in **23 languages**. [Privacy policy](docs/privacy-policy.md)

## Browse the poster wall at your desk

![The desktop app’s dark poster wall with the same demonstration film collection.](docs/showcase/07-desktop-en.webp)

On macOS and Windows, browse artwork, open series details and play a file without wearing a headset. The desktop app shares the library and metadata engine; spatial cinemas and seat selection are part of the PICO experience. For v1.0, [build the desktop app from source](docs/build.md).

## Your first screening

1. Download the APK from [GitHub Releases](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) and sideload it onto a compatible PICO OS 6 headset.
2. Open **Sources** to scan local video or add a network share. You can also open a single video straight from the system file manager.
3. Play a title and open **Viewing space** in the playback controls to choose a window, dark cinema or virtual room. Look under **Continue watching** when you return.

The v1.0 download is for PICO. The macOS and Windows desktop app is available as [source with build instructions](docs/build.md). Bring your own films; the titles and film artwork shown here were made for this demonstration. [About the images](docs/showcase/README.md) · [MIT license](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)

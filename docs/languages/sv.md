# Reality NAS Player

**En egen filmvisning, byggd för PICO Space Pro.**

**Svenska** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>Språk (23)</summary>

**Svenska** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[Språk (23) →](README.md)

</details>

![En egen filmvisning, byggd för PICO Space Pro. — Reality NAS Player](../showcase/00-screening-room-en.webp)

Öppna mapparna med filmer, serier och anime som du har samlat på din NAS. Reality NAS Player hittar titlar, sorterar avsnitt och lägger extramaterialet hos rätt serie. Välj ett omslag på **PICO Space Pro** och slå dig ner med utsikt över jorden.

**[Hämta APK för headset](https://github.com/jason-jm/reality-nas-player/releases/download/v1.0/reality-nas-player-1.0-4.apk)** · [Versionsinformation och kontrollsummor](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) · [Bygg datorappen](../build.md)

## Från NAS-mappar till filmuppgifter

![Två exempel visar hur en mapp och ett annat avsnitt kan ge titeln. Till höger syns fyra av åtta demofilmer som identifierats med NFO-filer på en WebDAV-resurs.](../showcase/01-auto-scraping-en.webp)

**Mappnamnet ger fler ledtrådar.** I `Interstellar (2014)/video1.mkv` ger mappen både titel och årtal. En fil som heter `Dark Staffel 2 Folge 3.mkv` hamnar som tredje avsnitt i andra säsongen. Spelaren kan även söka på originaltitlar och titlar från andra länder.

Om ett animeavsnitt bara heter `Seitokai` kan ett annat avsnitt i samma mapp ge hela namnet för sökningen på AniList. En biofilm kan få ett eget omslag, medan för- och eftertexternas bildsekvenser utan text hamnar bland seriens extramaterial. Två kopior av samma film kan dela post, så att du själv kan välja version.

Med en Kodi-NFO som innehåller ett TMDB-id kan spelaren läsa filmens eller seriens handling och rollista offline och använda dina omslag och bakgrundsbilder. Ange din egen API-nyckel för att söka på TMDB online. Om en serie fått fel titel kan du rätta hela posten på en gång. Valet finns kvar efter nästa genomsökning.

[Se exemplen och prova projektets öppna tester (på engelska)](../metadata-matching.md)

## Hitta filmen på omslaget

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="Biblioteket på PICO Space Pro visar åtta filmaffischer i två rader med fyra i varje. Kategorierna finns till vänster och reglagen för filtrering och sortering högst upp."></a>
</p>

Du kanske minns den blå affischen bättre än titeln. Bläddra bland omslagen och öppna ett för att se bakgrundsbild, handling och medverkande. För en serie kan du välja säsong och avsnitt. Filtrera på genre eller årtionde, eller fortsätt där du slutade.

## Anslut din samling

![Anslut din samling — Reality NAS Player](../showcase/02-connect-en.webp)

Öppna en lokal video eller en delad datormapp via SMB. WebDAV, NFS, FTP och SFTP stöds, liksom Jellyfin, Emby, Plex och DLNA. Aktivera DLNA-mottagaren när du vill skicka video från en kompatibel telefon.

## Välj salong och plats

![Välj salong och plats — Reality NAS Player](../showcase/03-cinemas-en.webp)

Rymdloungen har fem platser. Det finns också en privat biograf, ett bytorg och ett ökenläger. Salongen minns din plats och ljuset tänds när du pausar. Du kan även se filmen i ett svävande fönster.

## Se vad som finns bakom dig

![Se vad som finns bakom dig — Reality NAS Player](../showcase/04-panorama-en.webp)

Vänd dig om i 360° eller se scenen framför dig i 180° och VR180. I ett fönster kan du dra för att se dig omkring. 3D-film med bilder sida vid sida eller över varandra ger varje öga en egen vy.

## Välj ljud och text

![Välj ljud och text — Reality NAS Player](../showcase/05-playback-en.webp)

Byt till en extern dubbning, justera ljudfördröjningen och använd ASS/SSA-, PGS- eller VobSub-textning. Förutom MKV, MP4 och äldre format kan du spela huvudfilmen från en okrypterad Blu-ray- eller DVD-kopia utan skivmenyer. 4K och HDR beror på fil och enhet.

## Spara en film till resan

![Spara en film till resan — Reality NAS Player](../showcase/06-offline-en.webp)

Ladda ned innan du lämnar wifi. Med en egen SMB-resurs kan du välja att synka uppspelningsläge, favoriter och manuella matchningar mellan headset. Ingen reklam eller användningsanalys; offlineidentifiering använder lokala uppgifter.

## Vid skrivbordet

![Vid skrivbordet — Reality NAS Player](../showcase/07-desktop-en.webp)

I macOS och Windows kan du bläddra och spela upp med mus och tangentbord. De virtuella salongerna hör till headsetet. v1.0 innehåller en APK för headsetet; datorappen byggs från källkod.

## Första visningen

Installera APK-filen på PICO Space Pro, öppna en egen video eller lägg till en delad mapp och välj visningsmiljö i spelaren. Headsetbilderna kommer från emulatorn för PICO OS 6. Demofilmerna ingår inte.

[Integritet](../privacy-policy.md) · [Om bilderna](../showcase/README.md) · [MIT](../../LICENSE) · [Språk (23)](README.md)

# Reality NAS Player

**Een eigen filmzaal, ontworpen voor PICO Space Pro.**

**Nederlands** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>Talen (23)</summary>

**Nederlands** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[Talen (23) →](README.md)

</details>

**[Bekijk de film van 31 seconden](https://jason-jm.github.io/reality-nas-player/?watch=1)** · [Officiële website](https://jason-jm.github.io/reality-nas-player/)

![Een eigen filmzaal, ontworpen voor PICO Space Pro. — Reality NAS Player](../showcase/00-screening-room-en.webp)

Open de mappen met films, series en anime die je door de jaren heen op je NAS hebt verzameld. Reality NAS Player herkent titels, zet afleveringen op volgorde en bewaart de extra’s bij de serie. Kies op **PICO Space Pro** een hoes en neem plaats met uitzicht op de aarde.

**[Headset-APK downloaden](https://github.com/jason-jm/reality-nas-player/releases/download/v1.1.0/reality-nas-player-1.1.0-6.apk)** · [Versiegegevens en controlesommen](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.1.0) · [Desktopapp bouwen](../build.md)

## Van NAS-map naar filmgegevens

![Twee voorbeelden van herkenning via een mapnaam en een andere aflevering. Rechts staan vier van de acht demofilms die de headset via NFO-bestanden op een WebDAV-share heeft herkend.](../showcase/01-auto-scraping-en.webp)

**Ook de mapnaam telt mee.** Bij `Interstellar (2014)/video1.mkv` geeft de bovenliggende map de titel en het jaar. Een bestand met de naam `Dark Staffel 2 Folge 3.mkv` hoort bij seizoen 2, aflevering 3. De speler zoekt ook op oorspronkelijke titels en namen waaronder een film elders is uitgebracht.

Staat er op één anime-aflevering alleen `Seitokai`, dan kan een andere aflevering in dezelfde map de volledige naam leveren voor de zoekopdracht bij AniList. Een bioscoopfilm kan een eigen hoes krijgen, terwijl intro’s en outro’s zonder credits bij de extra’s van de serie komen. Twee versies van dezelfde film kun je vanuit één kaart afspelen.

Een Kodi-NFO met een TMDB-ID kan voor films en series ook offline de samenvatting en cast leveren, samen met de posters en achtergronden uit je map. Bij het scannen herkent de speler films aan de titel en het jaar en haalt hij beschikbare posters en samenvattingen op. Je hebt geen account of API-sleutel nodig. Een verkeerde koppeling herstel je voor de hele seriekaart. Die keuze blijft behouden bij een nieuwe scan.

[Bekijk de voorbeelden en probeer de tests van het project (Engels)](../metadata-matching.md)

## Blader langs de filmhoezen

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="De bibliotheek op PICO Space Pro toont acht filmposters in twee rijen van vier. Links staan de categorieën en bovenaan kun je filteren en sorteren."></a>
</p>

Weet je de titel niet meer, maar herinner je je de blauwe hoes? Op de posterwand herken je hem meteen. Open een film voor de achtergrond, het verhaal en de acteurs, of kies bij een serie een seizoen en aflevering. Filter op genre of decennium, of kijk verder waar je was gebleven.

## Sluit je eigen opslag aan

![Sluit je eigen opslag aan — Reality NAS Player](../showcase/02-connect-en.webp)

Open een bestand op de headset of een gedeelde map op de pc via SMB. Ook WebDAV, NFS, FTP, SFTP, Jellyfin, Emby, Plex en DLNA worden ondersteund. Zet de DLNA-ontvanger aan als je vanaf je telefoon wilt afspelen.

## Kies je stoel

![Kies je stoel — Reality NAS Player](../showcase/03-cinemas-en.webp)

De ruimtelounge heeft vijf zitplaatsen. Er zijn ook een privébioscoop, een dorpsplein en een kamp in de woestijn. Je stoel wordt onthouden en bij pauze gaat het licht aan. Je kunt altijd terug naar een zwevend venster.

## Kijk achterom

![Kijk achterom — Reality NAS Player](../showcase/04-panorama-en.webp)

Draai je om in een 360°-video of verken het beeld vóór je in 180° of VR180. In een venster sleep je de weergave. Bij 3D met beelden naast of boven elkaar krijgt elk oog zijn eigen beeld.

## Geluid en ondertitels

![Geluid en ondertitels — Reality NAS Player](../showcase/05-playback-en.webp)

Kies een originele of externe audiotrack en pas de vertraging aan. ASS/SSA, PGS en VobSub worden ondersteund. Naast MKV, MP4 en oudere formaten kun je de hoofdfilm uit een onversleutelde blu-ray- of dvd-back-up bekijken, zonder schijfmenu’s. 4K en HDR hangen af van bestand en apparaat.

## Neem een film mee

![Neem een film mee — Reality NAS Player](../showcase/06-offline-en.webp)

Download voordat je de wifi verlaat. Via een eigen SMB-share kun je desgewenst voortgang, favorieten en handmatige koppelingen tussen headsets synchroniseren. Geen advertenties of gebruiksanalyse; offline herkenning werkt met lokale bestandsinformatie.

## Aan je bureau

![Aan je bureau — Reality NAS Player](../showcase/07-desktop-en.webp)

Op macOS en Windows blader en speel je met muis en toetsenbord. De virtuele zalen zijn voor de headset. v1.1.0 biedt een headset-APK; de desktopapp bouw je uit de broncode.

## Je eerste voorstelling

Installeer de APK op PICO Space Pro, open je eigen video of voeg een gedeelde map toe en kies een kijkruimte tijdens het afspelen. De headsetbeelden komen uit de PICO OS 6-emulator; de voorbeeldfilms worden niet meegeleverd.

[Privacy](../privacy-policy.md) · [Over de beelden](../showcase/README.md) · [MIT](../../LICENSE) · [Talen (23)](README.md)

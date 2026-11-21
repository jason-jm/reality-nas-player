# Reality NAS Player

**La tua sala cinematografica, progettata per PICO Space Pro.**

**Italiano** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>Lingue (23)</summary>

**Italiano** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[Lingue (23) →](README.md)

</details>

**[Guarda il video di 31 secondi](https://jason-jm.github.io/reality-nas-player/?watch=1)** · [Sito ufficiale](https://jason-jm.github.io/reality-nas-player/)

![La tua sala cinematografica, progettata per PICO Space Pro. — Reality NAS Player](../showcase/00-screening-room-en.webp)

Apri le cartelle di film, serie e anime che hai raccolto negli anni sul NAS. Reality NAS Player ne riconosce i titoli, riordina gli episodi e mette gli extra accanto all’opera giusta. Su **PICO Space Pro** scegli una locandina e prendi posto con vista sulla Terra.

**[Scarica l’APK per il visore](https://github.com/jason-jm/reality-nas-player/releases/download/v1.1.0/reality-nas-player-1.1.0-6.apk)** · [Note di rilascio e checksum](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.1.0) · [Compila l’app desktop](../build.md)

## Dalle cartelle del NAS alle schede dei film

![Due esempi mostrano gli indizi dati dalla cartella e dagli episodi vicini. A destra il visore mostra quattro degli otto film dimostrativi identificati tramite i file NFO su WebDAV.](../showcase/01-auto-scraping-en.webp)

**Anche la cartella racconta qualcosa.** In `Interstellar (2014)/video1.mkv` è la cartella a fornire titolo e anno. Un episodio chiamato `Dark Staffel 2 Folge 3.mkv` finisce al suo posto, il terzo della seconda stagione. La ricerca considera anche il titolo originale e quelli usati nei diversi paesi.

Se il nome di un anime è abbreviato in `Seitokai`, un altro episodio nella stessa cartella può fornire il titolo completo da cercare su AniList. Il film uscito al cinema può avere una scheda propria, mentre le sigle senza crediti restano fra gli extra della serie. Due copie dello stesso film possono condividere una scheda da cui scegliere la versione da riprodurre.

Per film e serie, un NFO di Kodi con ID TMDB permette di recuperare offline trama e cast, insieme alle locandine e agli sfondi salvati sul NAS. Durante la scansione il lettore identifica i film dal titolo e dall’anno e cerca locandine e trame. Non servono un account o una chiave API. Se una serie è stata abbinata al titolo sbagliato basta correggere la sua scheda una volta. La scelta viene conservata alle scansioni successive.

[Scopri gli esempi e provali con i test del progetto (in inglese)](../metadata-matching.md)

## Sfoglia la libreria dalle locandine

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="La libreria su PICO Space Pro mostra otto locandine in due file da quattro, con le categorie a sinistra e i comandi per filtrare e ordinare in alto."></a>
</p>

Ti ricordi la locandina blu ma non il titolo? Apri il film dal mosaico di copertine per ritrovare sfondo, trama e interpreti. Per una serie scegli stagione ed episodio. Puoi filtrare per genere o decennio e riprendere un film dalla scena che avevi lasciato.

## Collega l’archivio che hai già

![Collega l’archivio che hai già — Reality NAS Player](../showcase/02-connect-en.webp)

Apri un video dal visore o una cartella condivisa sul computer tramite SMB. Sono disponibili anche WebDAV, NFS, FTP, SFTP, Jellyfin, Emby, Plex e DLNA. Per inviare video da un telefono compatibile attiva il ricevitore DLNA.

## Scegli la sala e il posto

![Scegli la sala e il posto — Reality NAS Player](../showcase/03-cinemas-en.webp)

Il salone spaziale ha cinque posti, anche vicino ai finestrini. Puoi scegliere il cinema privato, la piazza del borgo o il campo nel deserto. La sala ricorda dove eri seduto e le luci si riaccendono in pausa. Puoi anche guardare in una finestra sospesa.

## Guarda oltre l’inquadratura

![Guarda oltre l’inquadratura — Reality NAS Player](../showcase/04-panorama-en.webp)

Girati in un video a 360° o esplora la scena davanti a te in 180° o VR180. In finestra puoi trascinare la vista. Nei film 3D con immagini affiancate o sovrapposte ogni occhio riceve la propria immagine; il formato si può impostare a mano.

## La traccia che preferisci

![La traccia che preferisci — Reality NAS Player](../showcase/05-playback-en.webp)

Scegli fra audio originale e doppiaggio esterno, regola il ritardo audio e usa sottotitoli ASS/SSA, PGS o VobSub. Oltre a MKV, MP4 e diversi vecchi formati, puoi riprodurre il film principale dei backup Blu-ray o DVD non cifrati, senza i menu del disco. 4K e HDR dipendono dal file e dal dispositivo.

## Porta un film in viaggio

![Porta un film in viaggio — Reality NAS Player](../showcase/06-offline-en.webp)

Scaricalo sul visore prima di lasciare il Wi-Fi. Una condivisione SMB facoltativa può sincronizzare posizione, preferiti e abbinamenti corretti tra visori. Niente pubblicità né analisi d’uso; il riconoscimento offline usa i dati locali.

## Anche alla scrivania

![Anche alla scrivania — Reality NAS Player](../showcase/07-desktop-en.webp)

Su macOS e Windows puoi sfogliare la raccolta e riprodurre i file con mouse e tastiera. Le sale virtuali sono un’esperienza del visore. La v1.1.0 offre l’APK per il visore; l’app desktop si compila dai sorgenti.

## La prima proiezione

Installa l’APK su PICO Space Pro, apri un tuo video o aggiungi una cartella condivisa, poi scegli l’ambiente dai controlli di riproduzione. Le immagini del visore provengono dall’emulatore PICO OS 6. I film dimostrativi non sono inclusi.

[Privacy](../privacy-policy.md) · [Informazioni sulle immagini](../showcase/README.md) · [MIT](../../LICENSE) · [Lingue (23)](README.md)

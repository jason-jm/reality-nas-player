# Reality NAS Player

**Un cinematograf personal, creat pentru PICO Space Pro.**

**Română** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>Limbi (23)</summary>

**Română** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[Limbi (23) →](README.md)

</details>

![Un cinematograf personal, creat pentru PICO Space Pro. — Reality NAS Player](../showcase/00-screening-room-en.webp)

Deschide folderele cu filme, seriale și anime pe care le-ai strâns de-a lungul anilor pe NAS. Reality NAS Player recunoaște titlurile, ordonează episoadele și păstrează materialele bonus lângă serialul potrivit. Alege un afiș pe **PICO Space Pro** și ia loc cu vedere spre Pământ.

**[Descarcă APK-ul pentru cască](https://github.com/jason-jm/reality-nas-player/releases/download/v1.1.0/reality-nas-player-1.1.0-6.apk)** · [Note de versiune și sume de control](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.1.0) · [Compilare pentru calculator](../build.md)

## De la dosarele de pe NAS la fișa filmului

![Două exemple arată indiciile din numele folderului și dintr-un episod vecin. În dreapta apar patru dintre cele opt filme demonstrative identificate prin fișiere NFO pe o resursă WebDAV.](../showcase/01-auto-scraping-en.webp)

**Și numele folderului e un indiciu.** În `Interstellar (2014)/video1.mkv`, folderul dă titlul și anul. Fișierul `Dark Staffel 2 Folge 3.mkv` își găsește locul ca al treilea episod din sezonul doi. Căutarea ține cont și de titlul original și de cele sub care filmul a fost lansat în alte țări.

Dacă un episod anime apare doar ca `Seitokai`, un alt episod din același folder poate da numele complet pentru căutarea pe AniList. Lungmetrajul poate primi propriul afiș, iar secvențele de început și sfârșit fără generic intră la materialele bonus ale serialului. Două copii ale aceluiași film pot împărți o fișă din care alegi versiunea de redat.

Pentru filme și seriale, un fișier NFO Kodi cu un ID TMDB îți pune la dispoziție sinopsisul și distribuția offline, alături de afișele și fundalurile tale. La scanare, aplicația identifică filmele după titlu și an și caută afișele și descrierile disponibile, fără cont sau cheie API. O asociere greșită se poate corecta o singură dată pentru toată fișa serialului, iar alegerea se păstrează la următoarea scanare.

[Vezi exemplele și încearcă testele proiectului (în engleză)](../metadata-matching.md)

## Răsfoiește biblioteca după afișe

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="Biblioteca de pe PICO Space Pro arată opt afișe de film pe două rânduri de câte patru. Categoriile sunt în stânga, iar opțiunile de filtrare și sortare sunt sus."></a>
</p>

Îți amintești afișul albastru, dar nu și titlul? Deschide coperta pentru a vedea fundalul, rezumatul și distribuția. La seriale poți alege sezonul și episodul. Filtrează după gen sau deceniu ori reia un film din scena în care te-ai oprit.

## De pe discul sau serverul tău

![De pe discul sau serverul tău — Reality NAS Player](../showcase/02-connect-en.webp)

Deschide un fișier pe cască sau adaugă un dosar partajat prin SMB, WebDAV, NFS, FTP ori SFTP. Dacă ai deja Jellyfin, Emby, Plex sau un server DLNA, te poți conecta direct la el.

## Un loc lângă fereastră

![Un loc lângă fereastră — Reality NAS Player](../showcase/03-cinemas-en.webp)

În salonul orbital ai cinci locuri din care să privești Pământul. Pentru altă atmosferă, alege cinematograful privat, piața satului sau tabăra din deșert. Luminile se aprind când pui pauză; din comenzile de redare poți reveni la o fereastră plutitoare.

## Privește și în spatele camerei

![Privește și în spatele camerei — Reality NAS Player](../showcase/04-panorama-en.webp)

Într-un film la 360°, întoarce capul ca să descoperi celălalt mal. Înregistrările la 180° și VR180 se întind în fața ta. La filmele 3D cu imaginile alăturate sau suprapuse pe verticală, fiecare ochi primește propria imagine.

## Vocile și subtitrările pe care le preferi

![Vocile și subtitrările pe care le preferi — Reality NAS Player](../showcase/05-playback-en.webp)

Alege între dublaj și coloana sonoră originală, reglează întârzierea sunetului sau încarcă subtitrări externe. Sunt acceptate ASS/SSA, PGS și VobSub. Din copii Blu-ray și DVD necriptate poți viziona filmul principal, fără meniurile discului.

## Un film pentru drum

![Un film pentru drum — Reality NAS Player](../showcase/06-offline-en.webp)

Descarcă-l pe cască înainte să pleci de lângă Wi-Fi. Acasă poți sincroniza progresul, favoritele și corecturile de titlu între căști, printr-un dosar SMB ales de tine. Sincronizarea e opțională. Nu există reclame sau servicii de analiză a utilizării.

## Și la birou

![Și la birou — Reality NAS Player](../showcase/07-desktop-en.webp)

Pe macOS și Windows poți răsfoi colecția și reda filme cu mouse-ul și tastatura. Sălile virtuale sunt pentru cască. Versiunea 1.0 oferă un APK semnat; aplicația pentru calculator se compilează din codul sursă.

## Prima proiecție

Instalează fișierul APK pe PICO Space Pro, deschide propriul clip și alege spațiul de vizionare din comenzile playerului. Imaginile pentru cască provin din emulatorul PICO OS 6. Filmele demonstrative nu sunt incluse.

[Confidențialitate](../privacy-policy.md) · [Despre imagini](../showcase/README.md) · [MIT](../../LICENSE) · [Limbi (23)](README.md)

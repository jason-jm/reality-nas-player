# Reality NAS Player

**Votre salle de cinéma personnelle, conçue pour PICO Space Pro.**

**Français** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md)

<details>
<summary>Langues (23)</summary>

**Français** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[Langues (23) →](README.md)

</details>

![Votre salle de cinéma personnelle, conçue pour PICO Space Pro. — Reality NAS Player](../showcase/00-screening-room-en.webp)

Ouvrez les dossiers de films, de séries et d’anime que vous gardez sur votre NAS. Reality NAS Player y retrouve les titres, range les épisodes et rattache les bonus à leur œuvre. Sur **PICO Space Pro**, choisissez une affiche et installez-vous face à la Terre.

**[Télécharger l’APK](https://github.com/jason-jm/reality-nas-player/releases/download/v1.1.0/reality-nas-player-1.1.0-6.apk)** · [Notes de version et sommes de contrôle](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.1.0) · [Compiler la version de bureau](../build.md)

## Des dossiers du NAS aux fiches de films

![Le dossier parent et un épisode voisin permettent de retrouver deux titres. À droite, le casque affiche quatre des huit films de démonstration identifiés grâce aux NFO d’un partage WebDAV.](../showcase/01-auto-scraping-en.webp)

**Un nom de fichier ne dit pas tout.** Dans `Interstellar (2014)/video1.mkv`, le dossier donne le titre et l’année. Un épisode nommé `Dark Staffel 2 Folge 3.mkv` trouve sa place en saison 2, épisode 3. La recherche tient aussi compte du titre original et des titres de sortie selon les pays.

Pour un anime, si une équipe de fansub abrège le titre en `Seitokai`, un autre épisode du dossier peut fournir le nom complet à chercher sur AniList. Le film sorti en salles peut avoir sa propre affiche, tandis que les génériques sans crédits rejoignent les bonus de la série. Deux encodages d’un même film peuvent partager une fiche, avec le choix de la version à lire.

Pour les films et séries, un NFO Kodi contenant un identifiant TMDB permet de reprendre hors ligne le synopsis, la distribution et les illustrations conservées sur le NAS. Dès la première analyse, le lecteur reconnaît les films à partir du titre et de l’année et récupère les affiches et les résumés disponibles, sans compte ni clé API. Une correction apportée à la fiche d’une série vaut pour les fichiers qu’elle regroupe et reste en place après une nouvelle analyse.

[Explorer les exemples et les essayer avec les tests du projet (en anglais)](../metadata-matching.md)

## Retrouvez un film à son affiche

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="La bibliothèque sur PICO Space Pro affiche huit films sur deux rangées de quatre affiches. Les catégories sont à gauche, les filtres et le tri en haut."></a>
</p>

Vous vous souvenez de l’affiche bleue mais plus du titre ? Parcourez le mur d’affiches, puis ouvrez une fiche pour retrouver le visuel, le résumé et les interprètes. Les séries se parcourent par saison et épisode. Filtrez par genre ou par décennie, ou reprenez un film à la scène où vous l’avez laissé.

## Lire depuis votre installation actuelle

![Lire depuis votre installation actuelle — Reality NAS Player](../showcase/02-connect-en.webp)

Ouvrez un fichier dans le casque ou un dossier partagé en SMB sur votre ordinateur. WebDAV, NFS, FTP et SFTP sont aussi pris en charge, tout comme Jellyfin, Emby, Plex et DLNA. La réception DLNA depuis un téléphone s’active à la demande.

## Choisir sa place pour la séance

![Choisir sa place pour la séance — Reality NAS Player](../showcase/03-cinemas-en.webp)

Le salon spatial propose cinq sièges, dont deux près des hublots. Essayez aussi le cinéma privé, la place du village ou le campement dans le désert. La salle mémorise votre place et la lumière revient à la pause. Une fenêtre flottante reste possible.

## Regarder de l’autre côté du lac

![Regarder de l’autre côté du lac — Reality NAS Player](../showcase/04-panorama-en.webp)

Tournez la tête dans une vidéo à 360°, ou explorez la scène devant vous en 180° et VR180. Dans une fenêtre, faites glisser l’image. Les films 3D côte à côte ou dessus-dessous affichent une vue par œil ; le format peut être corrigé manuellement.

## Retrouver la bonne version

![Retrouver la bonne version — Reality NAS Player](../showcase/05-playback-en.webp)

Choisissez la VO ou un doublage externe, ajustez le décalage audio et les sous-titres. Le lecteur gère notamment ASS/SSA, PGS et VobSub. Il lit de nombreux formats anciens ainsi que le film principal d’une sauvegarde Blu-ray ou DVD non chiffrée, sans menus de disque. La 4K et le HDR dépendent du fichier et du matériel.

## Emporter le film hors ligne

![Emporter le film hors ligne — Reality NAS Player](../showcase/06-offline-en.webp)

Téléchargez avant de quitter le Wi-Fi. Un partage SMB de votre choix peut synchroniser progression, favoris et correspondances corrigées entre deux casques. C’est facultatif. Ni publicité ni mesure d’audience ; l’identification hors ligne utilise les informations locales.

## Au bureau aussi

![Au bureau aussi — Reality NAS Player](../showcase/07-desktop-en.webp)

Sur macOS et Windows, retrouvez les outils de bibliothèque et lisez vos fichiers à la souris et au clavier. Les salles virtuelles sont réservées au casque. La v1.1.0 fournit un APK pour casque ; l’application de bureau se compile à partir des sources.

## Première séance

Installez l’APK sur votre PICO Space Pro, ouvrez un fichier personnel ou ajoutez un dossier dans les sources. Choisissez ensuite l’espace de visionnage pendant la lecture. Les images du casque proviennent de l’émulateur PICO OS 6 ; les films de démonstration ne sont pas inclus.

[Confidentialité](../privacy-policy.md) · [À propos des images](../showcase/README.md) · [MIT](../../LICENSE) · [Langues (23)](README.md)

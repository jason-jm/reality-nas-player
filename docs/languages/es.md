# Reality NAS Player

**Tu sala de cine, diseñada para PICO Space Pro.**

**Español** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>Idiomas (23)</summary>

**Español** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[Idiomas (23) →](README.md)

</details>

![Tu sala de cine, diseñada para PICO Space Pro. — Reality NAS Player](../showcase/00-screening-room-en.webp)

Abre las carpetas de películas, series y anime que llevas años guardando en el NAS. Reality NAS Player reconoce títulos, ordena episodios y reúne cada serie con sus extras. En **PICO Space Pro**, elige un cartel y toma asiento con vistas a la Tierra.

**[Descargar APK para el visor](https://github.com/jason-jm/reality-nas-player/releases/download/v1.0/reality-nas-player-1.0-4.apk)** · [Notas de versión y sumas de verificación](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) · [Compilar para escritorio](../build.md)

## De las carpetas del NAS a las fichas de las películas

![Dos ejemplos muestran cómo ayudan la carpeta y otro episodio a reconocer un título. A la derecha se ven cuatro de las ocho películas de demostración identificadas por sus NFO en un recurso WebDAV.](../showcase/01-auto-scraping-en.webp)

**La carpeta también cuenta.** En `Interstellar (2014)/video1.mkv`, la carpeta da el título y el año. Un archivo llamado `Dark Staffel 2 Folge 3.mkv` se sitúa en el tercer episodio de la segunda temporada. La búsqueda también tiene en cuenta los títulos originales y los de estreno en otros países.

Si un grupo de fansub abrevia el nombre de un anime como `Seitokai`, otro episodio de la carpeta puede aportar el nombre completo para buscarlo en AniList. Una película de la saga puede tener su propio cartel y los openings y endings sin créditos quedan entre los extras de la serie. Dos copias de una misma película pueden compartir ficha para que elijas cuál reproducir.

Si ya guardas un NFO de Kodi con un identificador de TMDB, puedes recuperar sin conexión la sinopsis y el reparto de la película o serie y usar tus carteles y fondos. Para consultar TMDB en línea, introduce tu propia clave de API. Una sola corrección en la ficha de una serie se aplica a sus archivos y se conserva al volver a escanear.

[Ver los ejemplos y probarlos con los tests del proyecto (en inglés)](../metadata-matching.md)

## Explora la biblioteca por sus carteles

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="La biblioteca de PICO Space Pro muestra ocho carteles en dos filas de cuatro. Las categorías están a la izquierda y los controles de filtro y orden, arriba."></a>
</p>

Si solo recuerdas aquel cartel azul, búscalo en el muro de portadas. Abre la película para ver su fondo, la sinopsis y el reparto, o elige temporada y episodio en una serie. Filtra por género o década, o retoma una película en la escena donde la dejaste.

## Conecta lo que ya tienes

![Conecta lo que ya tienes — Reality NAS Player](../showcase/02-connect-en.webp)

Abre vídeos desde el visor o carpetas compartidas por SMB. También puedes usar WebDAV, NFS, FTP y SFTP, además de Jellyfin, Emby, Plex y DLNA. Para enviar vídeo desde un móvil compatible, activa el receptor DLNA.

## Elige dónde sentarte

![Elige dónde sentarte — Reality NAS Player](../showcase/03-cinemas-en.webp)

La sala espacial tiene cinco asientos, algunos junto a las ventanas. También hay un cine privado, una plaza y un campamento en el desierto. La sala recuerda tu asiento y se ilumina al pausar. Puedes volver a una ventana flotante cuando quieras.

## Mira qué hay al otro lado

![Mira qué hay al otro lado — Reality NAS Player](../showcase/04-panorama-en.webp)

Gira la cabeza en un vídeo de 360° o explora lo que tienes delante en 180° o VR180. Si lo ves en una ventana, arrastra la imagen. Las películas 3D en paralelo o una vista sobre otra muestran una imagen distinta a cada ojo; puedes corregir el formato manualmente.

## El sonido y los subtítulos de tu copia

![El sonido y los subtítulos de tu copia — Reality NAS Player](../showcase/05-playback-en.webp)

Cambia entre la pista original y un doblaje externo, ajusta el desfase del audio y elige subtítulos ASS/SSA, PGS o VobSub. Además de MKV, MP4 y formatos antiguos, puedes ver la película principal de una copia Blu-ray o DVD sin cifrar, sin los menús del disco. El resultado en 4K y HDR depende del archivo y del dispositivo.

## Descarga antes del viaje

![Descarga antes del viaje — Reality NAS Player](../showcase/06-offline-en.webp)

Guarda una película en el visor antes de salir de la red Wi-Fi. Si quieres, un recurso SMB propio sincroniza progreso, favoritos y correcciones de títulos entre visores. No hay anuncios ni analítica; la identificación sin conexión usa los datos del archivo.

## También en el escritorio

![También en el escritorio — Reality NAS Player](../showcase/07-desktop-en.webp)

En macOS y Windows puedes recorrer la biblioteca y reproducir archivos con el ratón y el teclado. Las salas virtuales son exclusivas del visor. La v1.0 incluye un APK para el visor; la aplicación de escritorio se compila desde el código fuente.

## Tu primera sesión

Instala el APK en PICO Space Pro, abre un vídeo tuyo o añade una carpeta compartida y selecciona el espacio de visualización desde los controles. Las capturas se hicieron en el emulador PICO OS 6. Las películas de muestra no están incluidas.

[Privacidad](../privacy-policy.md) · [Acerca de las imágenes](../showcase/README.md) · [MIT](../../LICENSE) · [Idiomas (23)](README.md)

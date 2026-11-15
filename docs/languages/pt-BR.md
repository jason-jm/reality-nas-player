# Reality NAS Player

**Um cinema particular feito para o PICO Space Pro.**

**Português (Brasil)** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>Idiomas (23)</summary>

**Português (Brasil)** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[Idiomas (23) →](README.md)

</details>

![Um cinema particular feito para o PICO Space Pro. — Reality NAS Player](../showcase/00-screening-room-en.webp)

Abra as pastas de filmes, séries e animes que você juntou no NAS ao longo dos anos. O Reality NAS Player identifica os títulos, ordena os episódios e reúne os extras de cada obra. No **PICO Space Pro**, escolha uma capa e assista com vista para a Terra.

**[Baixar APK do headset](https://github.com/jason-jm/reality-nas-player/releases/download/v1.0/reality-nas-player-1.0-4.apk)** · [Notas da versão e somas de verificação](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) · [Compilar para computador](../build.md)

## Das pastas do NAS às fichas dos filmes

![Exemplos de identificação pelo nome da pasta e pelo título de outro episódio. À direita, quatro dos oito filmes de demonstração que o visor identificou pelos NFOs em um compartilhamento WebDAV.](../showcase/01-auto-scraping-en.webp)

**A pasta também dá pistas.** Em `Interstellar (2014)/video1.mkv`, o nome da pasta informa o título e o ano. Um arquivo chamado `Dark Staffel 2 Folge 3.mkv` entra como o terceiro episódio da segunda temporada. A busca leva em conta o título original e os nomes de lançamento em outros países.

Se uma equipe de fansub abreviou o nome de um anime para `Seitokai`, outro episódio na pasta pode fornecer o título completo para a busca no AniList. O longa pode ganhar uma ficha própria, enquanto a abertura e o encerramento sem créditos ficam nos extras da série. Dá para guardar duas cópias do mesmo filme e escolher a versão em uma única ficha.

Em filmes e séries, um NFO do Kodi com ID do TMDB permite ler a sinopse e o elenco mesmo offline e aproveitar suas capas e imagens de fundo. Para consultar o TMDB online, informe sua própria chave de API. Se uma série foi identificada incorretamente, corrija a ficha uma vez. A escolha vale para os arquivos daquela ficha e permanece após uma nova varredura.

[Veja os exemplos e confira como executar os testes do projeto (em inglês)](../metadata-matching.md)

## Encontre o filme pela capa

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="A biblioteca no PICO Space Pro exibe oito pôsteres em duas fileiras de quatro. As categorias ficam à esquerda, com os controles de filtro e ordenação no topo."></a>
</p>

Esqueceu o título, mas lembra daquele pôster azul? Percorra o mural de capas e abra o filme para ver a arte de fundo, a sinopse e o elenco. Nas séries, escolha a temporada e o episódio. Filtre por gênero ou década ou continue uma sessão do ponto em que parou.

## Use o armazenamento que já tem

![Use o armazenamento que já tem — Reality NAS Player](../showcase/02-connect-en.webp)

Abra um vídeo no headset ou uma pasta compartilhada no computador via SMB. Há suporte a WebDAV, NFS, FTP e SFTP, além de Jellyfin, Emby, Plex e DLNA. Para enviar um vídeo do celular, ative a recepção DLNA.

## Escolha seu lugar

![Escolha seu lugar — Reality NAS Player](../showcase/03-cinemas-en.webp)

A sala espacial tem cinco assentos, incluindo lugares à janela. Há também o cinema privativo, a praça e o acampamento no deserto. A sala lembra onde você sentou e as luzes voltam ao pausar. Se preferir, assista em uma janela flutuante.

## Olhe para o outro lado do lago

![Olhe para o outro lado do lago — Reality NAS Player](../showcase/04-panorama-en.webp)

Gire a cabeça em um vídeo de 360° ou explore a cena à frente em 180° e VR180. Na janela, arraste para mudar o ângulo. Em filmes 3D lado a lado ou com vistas empilhadas, cada olho recebe sua própria imagem. É possível corrigir o formato manualmente.

## Escolha áudio e legendas

![Escolha áudio e legendas — Reality NAS Player](../showcase/05-playback-en.webp)

Alterne entre áudio original e dublagem externa, ajuste o atraso do som e use legendas ASS/SSA, PGS ou VobSub. Além de MKV, MP4 e formatos antigos, o player reproduz o filme principal de backups de Blu-ray e DVD sem criptografia, sem menus de disco. O resultado em 4K e HDR depende do arquivo e do aparelho.

## Leve um filme para a viagem

![Leve um filme para a viagem — Reality NAS Player](../showcase/06-offline-en.webp)

Baixe o filme antes de sair do Wi-Fi. Se quiser, use um compartilhamento SMB próprio para sincronizar progresso, favoritos e correspondências de títulos entre headsets. Não há anúncios nem análise de uso; a identificação offline usa informações do próprio arquivo.

## Na mesa de trabalho

![Na mesa de trabalho — Reality NAS Player](../showcase/07-desktop-en.webp)

No macOS e no Windows, navegue pela biblioteca e reproduza arquivos usando mouse e teclado. As salas virtuais são exclusivas do headset. A v1.0 oferece o APK do headset; o app para computador é compilado a partir do código-fonte.

## Sua primeira sessão

Instale o APK no PICO Space Pro, abra um vídeo seu ou adicione uma pasta compartilhada e escolha o ambiente nos controles de reprodução. As imagens do headset vêm do emulador PICO OS 6. Os filmes de demonstração não estão incluídos.

[Privacidade](../privacy-policy.md) · [Sobre as imagens](../showcase/README.md) · [MIT](../../LICENSE) · [Idiomas (23)](README.md)

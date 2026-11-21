# Reality NAS Player

**PICO Space Pro를 위한 나만의 영화관.**

**한국어** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>언어 (23)</summary>

**한국어** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[언어 (23) →](README.md)

</details>

**[31초 소개 영상 보기](https://jason-jm.github.io/reality-nas-player/?watch=1)** · [공식 웹사이트](https://jason-jm.github.io/reality-nas-player/)

![PICO Space Pro를 위한 나만의 영화관. — Reality NAS Player](../showcase/00-screening-room-en.webp)

오랫동안 NAS에 모아 둔 영화와 시리즈, 애니메이션 폴더를 그대로 열어 보세요. Reality NAS Player가 폴더와 다른 회차에 남은 단서를 읽어 작품을 정리합니다. **PICO Space Pro**에서 포스터를 고르고 지구가 보이는 좌석에서 감상하세요.

**[헤드셋 APK 받기](https://github.com/jason-jm/reality-nas-player/releases/download/v1.1.0/reality-nas-player-1.1.0-6.apk)** · [릴리스 및 체크섬](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.1.0) · [데스크톱 빌드](../build.md)

## NAS를 스캔해 작품 정보를 자동으로 채우세요

![상위 폴더와 이웃 회차에서 작품명을 찾는 두 사례. 오른쪽에는 WebDAV 공유 폴더의 NFO로 식별한 데모 영화 여덟 편 중 네 편이 보입니다.](../showcase/01-auto-scraping-en.webp)

**파일명이 짧아도 단서는 남습니다.** `Interstellar (2014)/video1.mkv`에서는 상위 폴더에서 제목과 연도를 찾습니다. 독일어로 적힌 `Dark Staffel 2 Folge 3.mkv`는 시즌 2의 3화로 읽습니다. 영화와 시리즈를 검색할 때는 원제와 지역별 개봉명도 대조합니다.

**자막판 애니메이션도 회차별로 정리합니다.** 어떤 파일에 `Seitokai`라고만 적혀 있어도 같은 폴더의 다른 회차에 있는 전체 제목으로 AniList를 검색할 수 있습니다. 극장판은 별도 작품으로, 논크레딧 오프닝과 엔딩은 본편의 특전으로 정리합니다. 같은 애니메이션 영화의 두 판본을 TMDB와 AniList가 각각 식별한 경우에도 원제와 개봉 연도가 일치하면 한 카드에서 판본을 골라 재생할 수 있습니다.

영화나 시리즈의 Kodi NFO에 TMDB ID가 있으면 오프라인에서도 거기에 저장된 줄거리와 출연진, 폴더의 포스터와 배경 이미지를 사용할 수 있습니다. NAS를 스캔하면 제목과 개봉 연도로 영화를 찾아 포스터와 줄거리를 가져옵니다. 계정이나 API 키는 필요 없습니다. 잘못 연결된 시리즈는 카드에서 한 번 수정하면 다시 스캔해도 선택이 유지됩니다.

[작품 식별 사례와 오픈 소스 테스트로 확인하는 방법 보기 (영어)](../metadata-matching.md)

## 포스터로 둘러보는 보관함

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="PICO Space Pro 라이브러리에 영화 포스터 여덟 장이 한 줄에 네 장씩 두 줄로 보인다. 왼쪽에는 카테고리, 위쪽에는 필터와 정렬 메뉴가 있다."></a>
</p>

제목이 가물가물해도 파란 포스터는 눈에 띕니다. 표지를 열어 배경 이미지와 줄거리, 출연진을 보거나 시리즈의 시즌과 회차를 고르세요. 장르와 연대로 범위를 좁히고, 이어 보기로 마지막 장면에 돌아갈 수 있습니다.

## 지금 쓰는 저장소 그대로

![지금 쓰는 저장소 그대로 — Reality NAS Player](../showcase/02-connect-en.webp)

헤드셋에 있는 동영상은 시스템 파일 관리자에서 엽니다. PC 공유 폴더에는 SMB를, NAS에는 WebDAV, NFS, FTP 또는 SFTP를 사용할 수 있습니다. Jellyfin, Emby, Plex와 DLNA도 지원합니다. 휴대폰에서 보내려면 DLNA 수신을 켜세요.

## 오늘의 상영관과 좌석

![오늘의 상영관과 좌석 — Reality NAS Player](../showcase/03-cinemas-en.webp)

우주 라운지의 다섯 좌석 중 창가에서는 지구를 볼 수 있습니다. 개인 영화관, 마을 광장, 사막 캠프도 있습니다. 좌석이 저장되고 일시 정지하면 조명이 밝아집니다. 주변을 보면서 감상하려면 떠 있는 창으로 바꾸세요.

## 고개를 돌려 보는 풍경

![고개를 돌려 보는 풍경 — Reality NAS Player](../showcase/04-panorama-en.webp)

360° 영상은 뒤쪽까지, 180°·VR180 영상은 앞쪽 풍경을 둘러볼 수 있습니다. 창에서는 드래그로 시선을 옮깁니다. 좌우 또는 상하 방식의 3D 영화는 눈마다 다른 화면을 보여 주며, 형식을 직접 지정할 수도 있습니다.

## 듣고 싶은 음성과 읽기 편한 자막

![듣고 싶은 음성과 읽기 편한 자막 — Reality NAS Player](../showcase/05-playback-en.webp)

내장·외부 자막, ASS/SSA 서식, PGS·VobSub 이미지 자막을 지원합니다. 더빙 트랙을 바꾸거나 블루투스 소리의 지연도 조절할 수 있습니다. MKV, MP4, 여러 구형 형식과 암호화되지 않은 블루레이·DVD 백업의 본편을 재생합니다. 디스크 메뉴는 지원하지 않으며 4K·HDR 재생은 기기와 파일에 따라 달라집니다.

## 출발 전에 한 편 저장

![출발 전에 한 편 저장 — Reality NAS Player](../showcase/06-offline-en.webp)

Wi-Fi를 떠나기 전에 헤드셋으로 내려받으세요. 원할 때만 자신의 SMB 공유 폴더를 통해 시청 위치, 즐겨찾기, 수동 매칭을 두 헤드셋에서 동기화할 수 있습니다. 광고나 사용 분석은 없으며 오프라인 식별도 선택할 수 있습니다.

## 책상에서도 꺼내 보는 소장품

![책상에서도 꺼내 보는 소장품 — Reality NAS Player](../showcase/07-desktop-en.webp)

macOS와 Windows 데스크톱 버전은 마우스와 키보드로 작품을 찾고 재생합니다. 공간 영화관은 헤드셋 전용입니다. v1.1.0은 헤드셋용 APK를 제공하며 데스크톱 버전은 소스에서 빌드합니다.

## 첫 상영 시작하기

PICO Space Pro에 APK를 사이드로드하고 자신의 동영상을 열거나 공유 폴더를 추가하세요. 재생 컨트롤에서 감상 공간을 고를 수 있습니다. 이미지는 PICO OS 6 에뮬레이터에서 캡처했으며 예시 영화는 포함되지 않습니다.

[개인정보 처리방침](../privacy-policy.md) · [이미지 안내](../showcase/README.md) · [MIT](../../LICENSE) · [언어 (23)](README.md)

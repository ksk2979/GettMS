# server-tools

서버 컴퓨터용 도구. 서버 폴더(start.bat 이 있는 곳)에 복사해서 쓴다.

| 파일 | 역할 |
|---|---|
| `sync-mods.bat` | 더블클릭용. GitHub 에서 최신 스크립트를 받은 뒤 실행 (서버 쪽 도구도 자동 갱신) |
| `sync-server-mods.ps1` | GitHub 의 `launcher/launcher.config.json` 을 읽어 서버 `mods` 폴더를 런처와 똑같이 맞춤. 서버를 끈 뒤 실행 |

## 모드 바꾸는 절차
1. GitHub 에서 `launcher/launcher.config.json` 의 `mods` 목록 수정 (Modrinth 슬러그)
2. 서버 컴퓨터: 서버 종료 → `sync-mods.bat` 더블클릭 → 서버 시작
3. 플레이어: 런처를 켜면 자동으로 같은 목록으로 패치됨

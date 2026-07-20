# Novel Library

개인용 TXT 소설 라이브러리 Android 앱입니다. Kotlin, Jetpack Compose, Room, WorkManager, Storage Access Framework 기반으로 로컬 DB를 먼저 표시하고 TXT 폴더를 증분 스캔하도록 구성했습니다.

## 빌드

```bash
gradle :app:assembleDebug
```

생성 APK: `app/build/outputs/apk/debug/app-debug.apk`


## GitHub Actions에서 APK 받기

이 저장소는 `.github/workflows/android-apk.yml` 워크플로를 포함합니다. GitHub에 push하거나 PR을 열면 GitHub Actions가 다음을 실행합니다.

1. JDK 17과 Android SDK를 준비합니다.
2. GitHub Actions가 Gradle 8.9를 준비하고 `gradle --no-daemon :app:testDebugUnitTest`와 Android lint를 실행합니다.
3. `gradle --no-daemon :app:assembleDebug`로 디버그 APK를 빌드하고 SHA-256 체크섬을 만듭니다.
4. Actions 실행 결과의 **Artifacts** 영역에 `novel-library-debug-apk` 이름으로 `app-debug.apk`를 업로드합니다.

수동으로 만들려면 GitHub 저장소의 **Actions > Build Android APK > Run workflow**를 누르면 됩니다. 내려받은 디버그 APK는 다음 명령으로 설치할 수 있습니다.

`v`로 시작하는 태그(예: `v0.1.0-test`)를 push하면 같은 워크플로가 GitHub **Releases**에 prerelease로 `app-debug.apk`와 SHA-256 파일을 첨부합니다. Actions artifact보다 바로 내려받기 쉬운 임시 배포 경로이며, 정식 서명 release APK가 아니라 테스트용 debug APK입니다.

```bash
adb install -r app-debug.apk
```

## 설치

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 사용 방법

1. 앱 실행 후 **TXT 폴더 선택/스캔**을 누릅니다.
2. Storage Access Framework 폴더 선택기에서 TXT 소설 폴더를 선택합니다.
3. 앱은 persistable URI 권한을 획득하고 하위 폴더를 포함해 `.txt` 파일을 스캔합니다.
4. 파일명에서 `[완결]`, 회차 범위, 외전 표기 등을 제거해 검색용 제목을 만들고 Room DB에 저장합니다.
5. 목록에서 즐겨찾기, 읽기 상태, 통합 검색을 사용할 수 있습니다.
6. 상세/편집에서 **TXT 열기**를 누르면 저장된 Document URI를 외부 텍스트 뷰어로 엽니다.
7. **백업**은 TXT 본문을 제외한 ZIP을 만들고, **복원**은 기존 작품과 정규화 제목을 기준으로 안전하게 병합합니다.

## 설계 요약

- 패키지명: `com.personal.novellibrary`
- DB 파일명: `novel_library.db`
- TXT 본문은 DB에 저장하지 않습니다.
- TXT 파일을 자동 삭제하거나 수정하지 않습니다.
- 플랫폼 정보는 `PlatformAdapter` 인터페이스와 플랫폼별 어댑터로 분리했습니다.
- 현재 공개 검색 어댑터는 가짜 작품 정보를 생성하지 않고 빈 결과를 반환하며, 실제 파서/공식 API 연결 시 `SearchCandidate`를 채우도록 되어 있습니다.
- 조회 상태는 `NOT_REQUESTED`, `LOADING`, `SUCCESS`, `NO_RESULT`, `FAILED`, `NEEDS_USER_CONFIRMATION`, `MANUAL`, `EXCLUDED`로 명시합니다.
- Room 마이그레이션을 제공하며 `fallbackToDestructiveMigration`을 사용하지 않습니다.
- 백업 ZIP은 TXT 본문을 제외합니다.

## 주요 기능 구현 위치

- Room 엔티티/DB: `app/src/main/java/com/personal/novellibrary/data`
- 제목/장르/매칭 로직: `app/src/main/java/com/personal/novellibrary/domain`
- SAF TXT 스캐너: `app/src/main/java/com/personal/novellibrary/scanner`
- 플랫폼 어댑터: `app/src/main/java/com/personal/novellibrary/platform`
- Compose UI/ViewModel: `app/src/main/java/com/personal/novellibrary/MainActivity.kt`
- 백업: `app/src/main/java/com/personal/novellibrary/backup`
- WorkManager 작업: `app/src/main/java/com/personal/novellibrary/worker`


## 현재 구현 상태

상세 구현률과 다음 우선순위는 `docs/PRD_COVERAGE.md`에 정리되어 있습니다.

기능 골격 기준 구현률과 상용 배포 준비도는 서로 다릅니다. 현재 상용 준비도는 약 **65%**이며, 공개 HTML 플랫폼 파서와 대규모 실기기 검증이 완료되기 전에는 테스트용 APK로 취급해야 합니다.

- SAF 기반 TXT 스캐너는 재스캔 시 기존 `documentUri`를 재사용하여 중복 작품 생성을 피하고, 크기/수정시간 변경 파일과 누락 파일을 구분합니다.
- 일괄 편집을 위한 Repository 계층이 즐겨찾기, 읽기 상태, 장르 변경, 휴지통 이동/복원을 트랜잭션 및 변경 이력과 함께 처리합니다.
- 스마트 컬렉션 조건 평가 로직은 장르, 즐겨찾기, 완결, 미독, 파일 존재 여부 조건을 검증합니다.
- CSV 내보내기와 ZIP 백업 구조를 제공하며 TXT 본문은 포함하지 않습니다.
- DataStore 기반 설정 저장소가 폴더 URI, 하위 폴더 포함 여부, Wi-Fi 전용 대량 검색, 리뷰 수집, 플랫폼 활성화 상태를 보관합니다.
- 진단 서비스가 앱 버전, DB 버전/용량, 작품 수, 폴더 접근 권한 개수를 텍스트로 내보낼 수 있게 준비되어 있습니다.
- DB v3에서 읽기 기록 필드를 추가했고, v4에서는 기존 중복 플랫폼 listing을 안전하게 정리한 후 작품·플랫폼 복합 unique index를 적용합니다.
- 오늘의 작품 추천 엔진이 숨김/삭제/파일 없음/추천 제외 조건을 반영해 후보를 고릅니다.
- 라이브러리 카드의 상세/편집 확장 영역에서 메모 저장, 개인 평점, 정보 잠금 상태를 수정할 수 있습니다.
- 목록에서 여러 작품을 선택해 일괄 즐겨찾기, 일괄 읽을 예정, 휴지통 이동을 수행할 수 있습니다.
- 휴지통 화면에서 라이브러리에서 제거한 작품을 확인하고 복원할 수 있습니다.
- 선택한 작품들을 새 일반 컬렉션명으로 묶어 컬렉션에 추가할 수 있습니다.
- 홈 화면에서 오늘의 추천, 백업 ZIP 내보내기, CSV 내보내기, 기본 스마트 컬렉션 생성을 실행할 수 있습니다.
- 목록에서 로판/BL/즐겨찾기 필터를 적용하고, 선택한 작품의 플랫폼 검색 작업을 WorkManager 큐에 넣을 수 있습니다.
- 플랫폼 검색 워커는 플랫폼별 실패를 격리하며, 모든 플랫폼이 실패한 경우에만 WorkManager 재시도를 요청합니다.
- 플랫폼 검색 워커는 가짜 데이터를 만들지 않고 NO_RESULT/FAILED/NEEDS_USER_CONFIRMATION 상태와 후보를 Room에 기록합니다.
- 검색 후보를 상세/편집 영역에서 확인하고 “이 작품” 또는 “제외”로 처리할 수 있습니다.
- 백업 ZIP의 `library.json`을 실제로 검증·파싱하여 기존 DB와 병합하고, 손상되었거나 과도하게 큰 백업은 오류로 처리합니다.
- 작품에 연결된 사용 가능한 Document URI를 이용해 TXT를 수정 없이 외부 뷰어로 열 수 있습니다.
- 설정 패널에서 하위 폴더 포함, Wi-Fi 전용 대량 검색, 리뷰 수집 여부를 DataStore에 보존합니다.
- 진단 패널에서 앱/DB 버전, DB 용량, 작품 수와 폴더 권한 수를 확인하고 결과를 클립보드에 복사할 수 있습니다.
- 상세 화면에서 사용자 태그를 추가·제거할 수 있고, 통합 검색은 사용자 태그, 별칭, 원본 파일명과 일반 컬렉션명도 검색합니다.
- 플랫폼 통합검색은 노벨피아·문피아·네이버 시리즈·카카오페이지·리디의 공개 검색 페이지에서 실제 작품 링크만 추출하고, 상세 페이지의 작가·줄거리·표지를 후보에 보강합니다. 접근 차단이나 HTML 변경은 조회 실패로 기록하며 가짜 작품 정보를 만들지 않습니다.
- 설정에서 검색 플랫폼을 개별 활성화하고 Wi-Fi 전용 여부를 정할 수 있으며 WorkManager 네트워크 제약에 반영됩니다.
- 플랫폼 작업은 Room에 진행·성공·결과 없음·실패 상태와 오류를 남기며 홈에서 집계를 확인할 수 있습니다.
- 완료된 플랫폼 조회는 7일 동안 재사용하여 반복 요청을 줄이고, 사용자가 제외한 후보는 다시 제안하지 않습니다. 필요하면 선택 작품을 강제 새로고침하거나 진행 작업을 취소할 수 있습니다.
- 플랫폼 새로고침이 실패해도 이전에 저장한 제목·작가·줄거리·표지를 지우지 않고 조회 상태와 오류만 갱신합니다.
- 작품 상세에서 플랫폼별 조회 상태·제목·작가·줄거리·오류를 확인하고 공개 원문을 열 수 있으며, 파일명 정규화가 틀리면 작품별 검색어로 다시 검색할 수 있습니다.
- 플랫폼에서 찾지 못한 작품은 제목·작가·줄거리·대표 장르를 직접 입력해 저장할 수 있고, 저장 시 자동 검색이 덮어쓰지 않도록 정보가 잠깁니다.
- TXT 스캔, 백업 ZIP, CSV, 복원 작업에는 인디케이터를 표시하고, 최근 플랫폼 작업에는 완료/전체 기반 진행률 바를 표시합니다.
- TXT 스캔은 SAF 탐색 결과를 모은 뒤 Room 단일 트랜잭션에서 신규·변경·누락을 반영하여 10,000개 URI를 `NOT IN` 바인딩하는 한계를 피합니다.
- GitHub Actions 에뮬레이터 작업은 2,000개 초기 import, 10,000개 확장, 10,000개 증분 스캔 10회와 5개 플랫폼 공개 검색 smoke를 실행하고 매일 보고서를 보존합니다. 초기 기준 수집 단계라 device-test 실패가 APK 생성을 막지는 않습니다.
- GitHub Actions는 단위 테스트와 lint 후 APK 및 SHA-256 체크섬, 테스트/lint 보고서를 artifact로 제공합니다.

## 테스트

```bash
gradle test
```

```bash
gradle connectedAndroidTest
```

`connectedAndroidTest`는 Android 에뮬레이터 또는 실제 기기가 필요합니다.

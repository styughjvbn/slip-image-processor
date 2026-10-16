# 전표 이미지 처리 — Java 21 / Spring Boot / OpenCV

사진의 문서 외곽 검출 → 원근·기울기 보정 → 표 수평·수직선 검출 → 고정 인쇄 패턴 제외 → 빈 행 제거를 구현한 프로젝트입니다. 이미지 연산은 **Java OpenCV만** 사용합니다. Python, OCR, 딥러닝 모델, 외부 이미지 API는 사용하지 않습니다.

**Green House에는 [같은 GitHub 저장소에 소스 모듈을 포함하는 방식](docs/MONOREPO_INTEGRATION.md)으로 이식합니다.** Bean·업무 연결은 [상세 이식 가이드](docs/MIGRATION_GREEN_HOUSE.md)를 따릅니다.

기본 운영 방식은 **Green House 안의 Gradle 하위 모듈**입니다. 이후 코어와 사용하는 코드를 같은 저장소에서 함께 수정·검증합니다. 별도 패키지 배포나 GitHub Packages 인증은 필요하지 않습니다.

Ubuntu에서 이 이미지 처리 저장소와 Green House 소스를 준비한 뒤, 이 저장소에서 실행합니다. 대상에 이미 코어 모듈이 있으면 덮어쓰지 않고 중단합니다.

```bash
bash scripts/install-green-house-module.sh "$HOME/work/green-house"

cd "$HOME/work/green-house/backend"
./gradlew :slip-image-core:test check bootJar --no-daemon
```

스크립트는 소스·테스트 복사와 Gradle·Docker 빌드 설정을 적용합니다. Bean·업무 호출 연결은 상세 가이드를 따라 추가하고, 관련 변경을 Green House에 함께 커밋합니다. Windows용 보조 스크립트는 `scripts/Install-GreenHouseModule.ps1`입니다.

```text
slip-image-core/   Spring 의존성이 없는 일반 JAR: 공개 API + OpenCV 구현
slip-image-demo/   Spring Boot 서버, 개발용 ZIP API, 배치 실행·갤러리
docs/             대상 아키텍처에 맞춘 배치·설정·연동 가이드
```

호스트 GitHub 저장소에 `slip-image-core`의 소스·빌드 설정을 포함하고 backend가 프로젝트 의존성으로 참조합니다. 코어에는 독립 Maven POM과 Gradle Java library 설정을 제공합니다. 공개 계약은 `kr.co.slip.api.ManagedSlipImageProcessor`이며 `kr.co.slip.opencv.SlipProcessors.bounded()`로 생성한 제한된 실행기를 호스트가 싱글톤 Bean으로 등록합니다. Spring 종료 시 `close()`를 연결합니다. 기존 패키지·실행 JAR 경로의 변경 사항도 가이드에 정리했습니다.

## 실행

JDK 21이 필요합니다. 처음 빌드할 때 Maven Central 연결이 필요하며 Maven Wrapper가 Maven 3.9.9를 다운로드합니다.

```powershell
.\mvnw.cmd test package

# 선택: 같은 PC의 독립 소비 프로젝트에서 확인할 때만 로컬 설치
.\mvnw.cmd -pl slip-image-core -am install

# raw-data의 이미지 일괄 처리; 원본은 변경하지 않음
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar --batch raw-data output

# Spring Boot 서버 실행 (기본 127.0.0.1:8080)
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar

# PowerShell에서는 curl 별칭 대신 curl.exe 사용
curl.exe -f -X POST "http://127.0.0.1:8080/api/slips/process" `
  -F "file=@raw-data/20260816_092640.jpg" `
  -F "ignoreRepeatedPrint=true" -F "protectedTopRows=2" -F "maxDimension=1600" `
  -o result.zip
```

## 실행 제어와 출력 모드

이식 시 다음처럼 생성합니다. 코어에는 Spring 의존성이 없습니다.

```java
try (ManagedSlipImageProcessor processor = SlipProcessors.bounded(
        new ExecutionSettings(2, 4, java.time.Duration.ofSeconds(30)), 1)) {
    var options = ProcessingOptions.defaults().withOutputMode(OutputMode.PRODUCTION);
    var result = processor.process(imageBytes, options); // 동기 대기, 같은 실행 제한 적용
    var future = processor.processAsync(() -> java.nio.file.Files.readAllBytes(path), options);
    var nextResult = future.get(); // 결과를 소비하고 참조를 해제
}
```

Spring에서는 요청마다 생성하지 말고 한 개의 Bean을 공유하고 `@Bean(destroyMethod="close")`로 종료합니다. 기본 OpenCV 내부 스레드는 1입니다. JVM 전체 설정이므로 서로 다른 값의 factory 초기화는 거부합니다. 데모 YAML의 `slip.execution.workers`, `queue-capacity`, `opencv-threads`, `shutdown-seconds`로 지정할 수 있습니다. worker/queue 한도는 이미지 작업 기준이며 전체 JVM 메모리나 완료 후 저장·ZIP 메모리를 제한하지 않습니다.

취소/인터럽트가 실행 중 JNI를 즉시 중단하지 않으며 실제 반환까지 worker를 점유합니다. close는 신규 제출을 거부하고 종료를 기다리며, 제한 시간 초과 시 대기 Future를 실패시키고 native 작업이 남아 있을 수 있음을 예외로 알립니다. 같은 worker callback/loader에서 동기 재진입이나 close를 하지 않습니다. 세부 생명주기 계약은 이식 가이드를 참조하세요.

| outputMode | 출력 |
|---|---|
| PRODUCTION | 보정 영상·표별 축약 PNG·종이 PNG·보고서 |
| REVIEW | PRODUCTION + 행 판정·종이 윤곽 표시 |
| ALL | REVIEW + 선 표시·수평/수직 마스크·종이 마스크 |

기존 결과 호환을 위해 기본값은 ALL입니다. `withOutputMode(...)`, API의 `-F "outputMode=PRODUCTION"`, CLI의 `--output-mode=PRODUCTION`으로 선택합니다. 미선택 영상은 복제·그리기·PNG 인코딩부터 생략합니다.

```powershell
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar --batch raw-data output rotation-overrides.example.json --workers=2 --queue-capacity=4 --opencv-threads=1 --output-mode=PRODUCTION
```

CLI는 제한된 수의 파일 경로를 제출하고 worker가 확보된 뒤 파일을 읽습니다. 완료 순으로 저장하며 summary/갤러리는 파일명 순입니다. 이전 실행의 미선택 진단 PNG는 해당 결과 폴더에서 정리합니다. 병렬 배치의 milliseconds에는 대기·저장 시간이 포함됩니다. 단일 처리 비교는 worker 1개와 같은 출력 모드로 측정하세요.

## 회전 처리

회전을 **90도 단위 방향 보정**과 **비스듬한 기울기 보정**으로 나누어 처리합니다. 기본 `rotationDegrees=-1`은 표선 방향으로 0/90/180/270도 중 하나를 추정하고, 표 상·하단의 검은 인쇄 밀도를 비교해 헤더가 위에 오도록 보완합니다. 정사각형이나 세로 사진에도 적용합니다. 기울기는 가로·세로 선의 각도를 90도 주기로 모아 추정하며, 12도보다 큰 회전도 보정합니다. 거의 45도인 경우에는 가로·세로 구분이 모호할 수 있습니다.

**OpenCV 표선만으로 글자의 정방향을 보장할 수는 없습니다.** 헤더가 없거나 하단 인쇄가 많은 양식에서는 자동 추정이 틀릴 수 있습니다. 이런 경우 `rotationDegrees`를 직접 지정하면 자동 방향 추정을 건너뜁니다. 각도는 **EXIF를 반영해 디코딩한 이미지에 추가 적용하는 시계 방향 회전**입니다.

| rotationDegrees | 처리 |
|---:|---|
| -1 | 자동 방향 추정 (기본) |
| 0 | 90도 단위 회전을 적용하지 않음 |
| 90 | 시계 방향 90도 |
| 180 | 180도 |
| 270 | 반시계 방향 90도 |

직접 지정해도 비스듬한 기울기 보정과 원근 보정은 계속 적용됩니다. API에서는 `-F "rotationDegrees=90"`으로 지정하고 Java에서는 다음처럼 호출합니다.

```java
ProcessingResult result = processor.process(imageBytes, ProcessingOptions.defaults().withRotation(90));
ProcessingResult.Rotation rotation = result.report().rotation();
// clockwiseDegrees: 실제 90도 단위 회전
// deskewCounterClockwiseDegrees: 이후 적용한 반시계 방향 기울기 보정 각도
// mode: AUTO / MANUAL, reviewRequired: 자동 방향 추정 검토 여부
```

일괄 처리에서는 네 번째 인수에 파일별 각도 JSON을 지정할 수 있습니다. JSON에 없는 파일은 자동 처리합니다. 예제의 다섯 파일은 결과를 직접 확인해 방향을 지정한 사례이며, JSON은 인수를 전달했을 때만 사용합니다. 특히 `20260816_092020.jpg`는 하단 인쇄 때문에 자동 모드에서 180도로 오판해 0도로 지정했습니다.

```powershell
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar --batch raw-data output rotation-overrides.example.json
```

`rotation-overrides.example.json` 예: `{ "20260816_091751.jpg": 90 }`. 45도 등 지원하지 않는 직접 지정값은 거부합니다. 표·행 좌표는 최종 보정 이미지 기준이고, 문서 모서리 좌표는 90/180/270도 회전을 역변환해 원본 좌표로 반환합니다.

서버 요청은 ZIP으로 응답합니다. 일괄 실행은 `output/<원본파일명>/`에 같은 결과를 생성하고 `output/summary.json`에 파일별 처리 통계와 실패를 기록합니다. **`output/index.html`을 열면 전체 결과를 이미지로 비교할 수 있습니다.** 입력과 출력 폴더는 서로 분리해야 합니다. 일괄 실행 중에는 실행 중인 JAR을 덮어쓰지 마세요.

## 결과 파일

| 파일 | 내용 |
|---|---|
| `01-corrected.png` | 원근 또는 회전 보정된 사진 |
| `02-lines.png` | 빨간 수평선 / 초록 수직선 표시 |
| `03-horizontal.png`, `04-vertical.png` | 각 방향의 이진 선 마스크 |
| `05-row-decisions.png` | 제거 대상으로 판정한 행을 빨간 사각형으로 표시 |
| `06-paper-contours.png` | 추정한 종이 윤곽(자홍색), 종이 사이 분리선(하늘색) |
| `07-paper-mask.png` | 실제 분리에 사용한 종이 영역 마스크 |
| `paper-01.png` 등 | 종이/전표별 분리 이미지, 배경은 투명한 4채널 PNG |
| `table-01-compacted.png` 등 | **표 영역별로 빈 행을 실제 잘라내 이어 붙인 이미지** |
| `report.json` | 보정 방식, 표 좌표, 행별 잉크 비율, 삭제 여부·이유, 경고 |

전체 문서에서는 표 옆의 별도 내용이 함께 잘릴 수 있으므로 행을 잘라내지 않습니다. 원문 전체는 `01-corrected.png`에 보존하고, 빈 행 제거 결과는 **표별 이미지**로 제공합니다. 겹쳐 가려진 전표 내용이나 사진 밖 내용은 복원하지 않습니다.

모든 표·행 좌표는 `01-corrected.png` 기준 픽셀 좌표입니다. `top`은 포함, `bottom`은 제외합니다. `documentCorners`는 문서 외곽을 실제 검출한 경우에만 제공하며, EXIF 방향을 반영해 디코딩한 원본 이미지의 좌표입니다. 기울기 보정 후의 최종 출력 모서리와는 다릅니다. 행 번호는 0부터 시작합니다.

## 종이 윤곽과 전표별 분리

회전·원근 보정된 이미지에서 종이와 책상 배경을 구분하고, 겹친 종이에 관측 가능한 경계가 있으면 전표별 `paper-01.png`, `paper-02.png`를 생성합니다. 이 이미지에는 빈 행을 제거하지 않은 전표 영역을 보존하며, 표만 축약한 결과는 기존 `table-XX-compacted.png`로 별도 제공합니다. API ZIP과 일괄 결과 갤러리 모두 분리한 종이를 포함합니다.

1. 표 내부의 종이 색상을 Lab 공간에서 추정하고 foreground/background 마스크를 만듭니다. 표가 없는 경우에는 중앙 영역을 후보로 사용하되 배경 대비가 없으면 분리하지 않습니다.
2. OpenCV GrabCut으로 종이와 배경을 구분하고 외부 컨투어를 구합니다. 이미 문서 네 모서리를 확정해 보정했다면 그 문서를 하나의 종이로 사용합니다.
3. 위아래로 겹친 전표는 표 영역 사이에서 긴 종이 경계선과 양쪽 가장자리의 색상 변화를 찾습니다. 밝기와 Lab 색상 채널을 함께 사용하고, 끊어진 선 조각도 결합합니다. 경계를 찾은 경우에만 분할하며, 떨어져 있는 종이는 연결 성분별로 각각 분리합니다. 표가 두 개라는 이유만으로 종이를 두 장으로 나누지는 않습니다.
4. 전표를 나눈 뒤 각각의 볼록 포락선(convex hull)을 구해 인쇄, 구멍, 손가락 때문에 글씨 영역이 잘려 나가는 것을 줄입니다. 이 때문에 작은 배경 영역이나 손가락이 포함될 수 있습니다. 해당 영역의 실제 원본 픽셀을 유지하며 가려진 글씨를 생성하거나 복원하지 않습니다.
5. 윤곽 밖은 alpha=0인 투명 PNG로 저장합니다. 가려진 윗 전표는 **사진에 보이는 부분만** 출력합니다. 완전한 종이 크기나 가려진 내용을 추정해 채우지 않습니다.

`report.json`의 `papers`에는 각 종이의 `bounds`, `contour`, `method`, `reviewRequired`, `tableIndices`가 기록됩니다. 좌표는 **최종 보정 이미지 기준**이며, 분리한 PNG의 좌상단은 `bounds.x`, `bounds.y`에 해당합니다. `tableIndices`로 같은 전표에 있는 표 결과를 연결할 수 있습니다.

| method | 의미 |
|---|---|
| DOCUMENT_QUAD | 네 모서리를 확정한 문서를 사용 |
| CONNECTED_PAPER_REGION | 색상·외곽으로 구한 하나의 연결된 종이 후보 |
| VISIBLE_EDGE_SPLIT | 연결된 종이 후보를 관측한 겹침 경계에서 분할 |

겹침 경계가 불확실하면 한 영역으로 유지하고 경고를 남깁니다. 같은 색 배경, 강한 그림자, 구겨짐, 종이가 비스듬하게 서로 교차한 경우에는 오차가 생길 수 있습니다. `CONNECTED_PAPER_REGION`은 실제 한 장임을 확정한다는 뜻이 아니며, 윤곽 추정의 `reviewRequired=true` 결과는 `06-paper-contours.png`와 함께 검토하세요.

## 처리 방식

1. **입력 정규화:** `maxDimension`(기본 2,200px)을 넘는 이미지를 비율을 유지해 축소합니다. 기울기를 보정한 분석용 영상에서 표선 방향과 헤더 인쇄 밀도를 비교해 0/90/180/270도 회전을 추정하거나 지정한 회전을 적용합니다. 회전 방식과 실제 각도를 보고서에 표시합니다.
2. **외곽 검출:** GaussianBlur → Canny → closing → contour → 4점 다각형 근사. 면적, 볼록성, 프레임 접촉, 경계 안팎의 색상 대비를 검사해 종이 내부 표 테두리의 오인을 줄입니다.
3. **원근 보정:** 네 점을 찾으면 `getPerspectiveTransform` + `warpPerspective`로 문서를 펼칩니다 (`DOCUMENT_PERSPECTIVE`). 찾지 못하면 Hough 선의 두 방향과 위치별 기울기를 Theil–Sen 방식으로 추정해 사진 전체에 적용합니다 (`GRID_PERSPECTIVE`). 격자 추정도 불확실하면 회전만 보정하고 사진을 유지합니다 (`FRAME_DESKEW`). 후자의 두 경우 보고서에 경고를 남깁니다.
4. **표 선 검출:** adaptive threshold 후 긴 가로·세로 morphology opening. 비슷한 시작·끝 x 좌표를 갖는 수평선들을 묶고 수직선 지지 여부를 확인합니다. 투영으로 행/열 경계를 검출하며 조금 기울어진 선을 허용합니다. 병합 셀의 모든 짧은 선을 복원하는 기능은 아닙니다.
5. **고정 인쇄 패턴:** 비슷한 높이의 후보 행이 5개 이상이면 행을 기준 높이 64px에 처리 배율을 적용한 높이로 정규화합니다. 행의 70% 이상에서 반복되는 픽셀 주변을 고정 인쇄로 추정해 제거합니다. 파란색 잉크는 별도 HSV 마스크로 보존합니다.
6. **빈 행 판정:** 격자 마스크를 빼고 반복 인쇄를 제외한 뒤 연결 성분 면적과 잉크 비율을 계산합니다. 작은 잡음은 제외하고, 첫 2개 행과 높이가 다른 병합/제목 행은 보존합니다. 합계·취소선·서명도 잉크로 취급합니다.

## 기존 Spring Boot 프로젝트에서 사용

**권장 방식은 Spring 의존성이 없는 코어 소스 모듈을 같은 GitHub 저장소에 포함해 함께 빌드하는 것입니다.** 결과물은 일반 JAR입니다. 공개 계약은 `kr.co.slip.api`, 실행 제어는 `kr.co.slip.execution`, OpenCV 구현은 `kr.co.slip.opencv`에 있습니다. 호스트에서 `SlipProcessors.bounded()`를 Bean으로 등록하고 종료 시 `close()`를 호출합니다. 소스 복사 방식과 Green House 기능별 배치는 [이식 가이드](docs/MIGRATION_GREEN_HOUSE.md)를 따릅니다. OpenPnP 패키지는 OpenCV Java API와 운영체제별 네이티브 라이브러리를 묶어 배포하며, 픽셀 처리는 `org.opencv` API가 수행합니다. 현재 샘플 앱의 Spring Boot 버전은 3.5.0입니다. 호스트의 Spring Boot 버전을 유지하며 데모 서버·Controller·설정을 이식할 필요는 없습니다.

```java
ProcessingResult result = processor.process(imageBytes, ProcessingOptions.defaults());
byte[] correctedPng = result.images().get("01-corrected.png");
List<ProcessingResult.Table> tables = result.report().tables();
List<ProcessingResult.Paper> papers = result.report().papers();
if (!papers.isEmpty()) {
    byte[] firstPaperPng = result.images().get("paper-01.png");
}
```

`NativeOpenCv.load()`는 JVM당 한 번만 네이티브 라이브러리를 로드합니다. 엔진은 요청별 로컬 상태를 사용하며 `Mat`/ROI/커널/컨투어의 픽셀 참조를 단계별 scope에서 명시적으로 해제합니다. `setTo` 반환 Mat도 등록하고 인코딩 버퍼는 복사 직후 해제합니다. 결과는 Java byte 배열과 DTO이므로 호출자가 `Mat.release()`를 호출할 필요가 없습니다. 이식용 코어의 제한된 실행기는 기본 worker 2개·대기열 4개를 사용하며 초과 접수를 거절합니다. 데모 API는 이를 HTTP 429로 변환합니다. HTTP와 CLI 모두 같은 라이브러리 실행 제어를 사용합니다. 제한은 공유한 실행기 인스턴스 단위이며 저수준 `new SlipProcessor()`는 이 제한을 우회합니다.

## 조정할 값

`ProcessingOptions` 생성자로 조정합니다. API에서는 `maxDimension`, `ignoreRepeatedPrint`, `protectedTopRows`, `rotationDegrees`를 전달할 수 있습니다. 기존 5개 인수를 받는 생성자는 자동 회전 모드로 유지합니다.

| 옵션 | 기본값 | 의미 |
|---|---:|---|
| `maxDimension` | 2200 | 작업 이미지 최대 변 길이 (800–3000) |
| `protectedTopRows` | 2 | 각 표 상단에서 무조건 보존할 행 수 |
| `ignoreRepeatedPrint` | true | 반복되는 고정 인쇄를 제외할지 여부 |
| `minimumInkRatio` | 0.00035 | 이 값 미만의 유효 잉크 비율이면 빈 행 후보 |
| `minimumComponentArea` | 8 | 기준 크기에서의 연결 성분 최소 면적 (실제 적용 시 배율² 반영) |
| `rotationDegrees` | -1 | 자동(-1) 또는 시계 방향 0/90/180/270도 회전 |
| `outputMode` | ALL | PRODUCTION / REVIEW / ALL. 운영에서는 PRODUCTION 명시 권장 |

`maxDimension`은 기본 2200, 허용 범위 800–3000입니다. API에서 생략하면 기존 크기를 유지하고 범위를 벗어나면 HTTP 400을 반환합니다. Java와 일괄 처리에서도 지정할 수 있습니다.

```java
ProcessingOptions options = ProcessingOptions.defaults().withMaxDimension(1600).withRotation(0);
ProcessingResult result = processor.process(imageBytes, options);
```

```powershell
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar --batch raw-data output rotation-overrides.example.json --max-dimension=1600
# 회전 JSON 없이도 사용 가능
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar --batch raw-data output --max-dimension=1600
```

공간 임계값은 **같은 원본을 기본 2200 제한으로 처리했을 때**를 기준으로 비례합니다.

`배율 = min(원본 긴 변, maxDimension) / min(원본 긴 변, 2200)`

- 길이: `기준값 × 배율`. 선 간격·연결 허용 거리·테두리 여백·커널 크기·행 정규화 높이·Hough 투표 수에 반영합니다. 선 추출 커널의 길이는 이미지 크기 비율도 계속 사용합니다.
- 면적: `기준값 × 배율²`. 유효 연결 성분 최소 면적 등에 반영합니다.
- 이진화/Gaussian 윈도는 3 이상의 홀수로, 길이는 최소 1픽셀로 반올림합니다. 선 커널의 단일 픽셀 축은 유지합니다.
- 밝기·색상·각도·잉크 비율·면적 비율·반복 횟수는 해상도에 비례시키지 않습니다.
- 종이/배경 분석은 내부적으로 최대 900px를 유지합니다. 이 좌표계의 임계값에는 별도 배율을 적용해 이중 축소를 피합니다. 900px 미만으로 작아지는 경우에는 해당 임계값도 줄어듭니다.

예를 들어 긴 변 4000px 원본을 1100px로 처리하면 기본 대비 배율은 0.5이고 최소 연결 성분 면적은 8→2가 됩니다. 긴 변 1000px 원본에서 제한을 2200→3000으로 바꿔도 실제 이미지와 임계값은 바뀌지 않습니다. 작은 원본은 확대하지 않으며, 결과는 원본 해상도로 복원하지 않습니다. 보정 후 캔버스는 회전/원근 변환으로 제한값보다 커질 수 있습니다. 해상도를 낮추면 작은 글씨가 사라질 수 있으므로 비례 조정이 모든 크기에서 동일한 판정을 보장하지는 않습니다.

빈 행이 남으면 `05-row-decisions.png`와 행별 `inkRatio`를 먼저 확인하세요. 임계값을 올리면 더 많은 행이 제거되며, 작은 필기를 놓칠 가능성도 커집니다. 양식별 기준을 정한 후 사용하는 것이 좋습니다.

[성능·메모리 검증](docs/PERFORMANCE.md)에 단일 실행과 반복·병렬 실행의 실측 및 한계를 기록했습니다.

## 적용 범위와 검증

이 코드는 범용 문서 인식기가 아닌 OpenCV 기반 휴리스틱 구현입니다. **고정 인쇄와 반복되는 흑백 기입 내용은 픽셀만으로 완전히 구분할 수 없습니다.** 동일한 흑백 값이 많은 행에서 반복되면 고정 인쇄로 오인할 수 있습니다. 중요한 전표는 `report.json`과 행 표시 이미지를 검토하고, 이런 양식에서는 `ignoreRepeatedPrint=false`로 처리하세요. 잉크가 흐리거나 표 선에 겹치면 누락될 수 있고, 도장·배경 얼룩은 빈 행을 유지시키기도 합니다.

원본이 잘리거나 겹친 경우 완전한 문서 외곽 검출은 불가능할 수 있습니다. 겹친 여러 종이는 하나의 평면이 아니므로 표 기반 전역 원근 변환은 근사입니다. 모서리를 확정하지 못하면 문서를 임의로 잘라내지 않고 경고와 함께 보존합니다. 가로·세로 선이 거의 없거나 심하게 휘어진 양식은 별도 대응이 필요합니다.

합성 테스트는 잘못된 입력, 무표 문서 보존, 인쇄 반복 제외, 파란/검은 글씨 보존, 문서 4점 원근 보정, 잘린 문서의 격자 원근 보정 및 HTTP ZIP 응답/400 응답을 검사합니다. 실제 샘플 실행 결과와 정량적 한계는 `VALIDATION.md`에 기록합니다. 실제 사진에는 행별 정답 라벨이 없으므로 처리 성공 수는 판정 정확도를 의미하지 않습니다.

참고: [OpenCV Java Imgproc API](https://docs.opencv.org/4.7.0/javadoc/org/opencv/imgproc/Imgproc.html), [OpenPnP OpenCV](https://github.com/openpnp/opencv), [Spring Boot](https://spring.io/projects/spring-boot).

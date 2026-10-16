# 전표 이미지 처리 — Java 21 / Spring Boot / OpenCV

사진의 문서 외곽 검출 → 원근·기울기 보정 → 표 수평·수직선 검출 → 고정 인쇄 패턴 제외 → 빈 행 제거를 구현한 프로젝트입니다. 이미지 연산은 **Java OpenCV만** 사용합니다. Python, OCR, 딥러닝 모델, 외부 이미지 API는 사용하지 않습니다.

## 실행

JDK 21이 필요합니다. 처음 빌드할 때 Maven Central 연결이 필요하며 Maven Wrapper가 Maven 3.9.9를 다운로드합니다.

```powershell
.\mvnw.cmd test package

# raw-data의 이미지 일괄 처리; 원본은 변경하지 않음
java -Xmx1200m -jar target/slip-image-processor-1.0.0.jar --batch raw-data output

# Spring Boot 서버 실행 (기본 127.0.0.1:8080)
java -Xmx1200m -jar target/slip-image-processor-1.0.0.jar

# PowerShell에서는 curl 별칭 대신 curl.exe 사용
curl.exe -f -X POST "http://127.0.0.1:8080/api/slips/process" `
  -F "file=@raw-data/20260816_092640.jpg" `
  -F "ignoreRepeatedPrint=true" -F "protectedTopRows=2" `
  -o result.zip
```

서버 요청은 ZIP으로 응답합니다. 일괄 실행은 `output/<원본파일명>/`에 같은 결과를 생성하고 `output/summary.json`에 파일별 처리 통계와 실패를 기록합니다. **`output/index.html`을 열면 전체 결과를 이미지로 비교할 수 있습니다.** 입력과 출력 폴더는 서로 분리해야 합니다. 일괄 실행 중에는 실행 중인 JAR을 덮어쓰지 마세요.

## 결과 파일

| 파일 | 내용 |
|---|---|
| `01-corrected.png` | 원근 또는 회전 보정된 사진 |
| `02-lines.png` | 빨간 수평선 / 초록 수직선 표시 |
| `03-horizontal.png`, `04-vertical.png` | 각 방향의 이진 선 마스크 |
| `05-row-decisions.png` | 제거 대상으로 판정한 행을 빨간 사각형으로 표시 |
| `table-01-compacted.png` 등 | **표 영역별로 빈 행을 실제 잘라내 이어 붙인 이미지** |
| `report.json` | 보정 방식, 표 좌표, 행별 잉크 비율, 삭제 여부·이유, 경고 |

전체 문서에서는 표 옆의 별도 내용이 함께 잘릴 수 있으므로 행을 잘라내지 않습니다. 원문 전체는 `01-corrected.png`에 보존하고, 빈 행 제거 결과는 **표별 이미지**로 제공합니다. 겹쳐 가려진 전표 내용이나 사진 밖 내용은 복원하지 않습니다.

모든 표·행 좌표는 `01-corrected.png` 기준 픽셀 좌표입니다. `top`은 포함, `bottom`은 제외합니다. `documentCorners`는 문서 외곽을 실제 검출한 경우에만 제공하며, EXIF 방향을 반영해 디코딩한 원본 이미지의 좌표입니다. 기울기 보정 후의 최종 출력 모서리와는 다릅니다. 행 번호는 0부터 시작합니다.

## 처리 방식

1. **입력 정규화:** 최대 변 2,200px로 축소합니다. 가로 사진에서 긴 수직선이 우세하면 90도 회전을 추정합니다. 검은 인쇄의 분포로 시계/반시계 방향을 추정하지만 선만으로 상하 방향을 확정할 수 없으므로 회전 추정은 보고서에 표시합니다.
2. **외곽 검출:** GaussianBlur → Canny → closing → contour → 4점 다각형 근사. 면적, 볼록성, 프레임 접촉, 경계 안팎의 색상 대비를 검사해 종이 내부 표 테두리의 오인을 줄입니다.
3. **원근 보정:** 네 점을 찾으면 `getPerspectiveTransform` + `warpPerspective`로 문서를 펼칩니다 (`DOCUMENT_PERSPECTIVE`). 찾지 못하면 Hough 선의 두 방향과 위치별 기울기를 Theil–Sen 방식으로 추정해 사진 전체에 적용합니다 (`GRID_PERSPECTIVE`). 격자 추정도 불확실하면 회전만 보정하고 사진을 유지합니다 (`FRAME_DESKEW`). 후자의 두 경우 보고서에 경고를 남깁니다.
4. **표 선 검출:** adaptive threshold 후 긴 가로·세로 morphology opening. 비슷한 시작·끝 x 좌표를 갖는 수평선들을 묶고 수직선 지지 여부를 확인합니다. 투영으로 행/열 경계를 검출하며 조금 기울어진 선을 허용합니다. 병합 셀의 모든 짧은 선을 복원하는 기능은 아닙니다.
5. **고정 인쇄 패턴:** 비슷한 높이의 후보 행이 5개 이상이면 행을 높이 64px로 정규화합니다. 행의 70% 이상에서 반복되는 픽셀 주변을 고정 인쇄로 추정해 제거합니다. 파란색 잉크는 별도 HSV 마스크로 보존합니다.
6. **빈 행 판정:** 격자 마스크를 빼고 반복 인쇄를 제외한 뒤 연결 성분 면적과 잉크 비율을 계산합니다. 작은 잡음은 제외하고, 첫 2개 행과 높이가 다른 병합/제목 행은 보존합니다. 합계·취소선·서명도 잉크로 취급합니다.

## 기존 Spring Boot 프로젝트에서 사용

`org.openpnp:opencv:4.9.0-0` 의존성과 `NativeOpenCv`, `ProcessingOptions`, `ProcessingResult`, `SlipProcessor` 클래스를 가져오면 됩니다. OpenPnP 패키지는 OpenCV Java API와 운영체제별 네이티브 라이브러리를 묶어 배포하며, 픽셀 처리는 `org.opencv` API가 수행합니다. 현재 샘플 앱의 Spring Boot 버전은 3.5.0입니다. 기존 서비스의 Spring Boot 버전을 유지하고 처리 클래스를 서비스로 주입할 수 있습니다.

```java
ProcessingResult result = processor.process(imageBytes, ProcessingOptions.defaults());
byte[] correctedPng = result.images().get("01-corrected.png");
List<ProcessingResult.Table> tables = result.report().tables();
```

`NativeOpenCv.load()`는 JVM당 한 번만 네이티브 라이브러리를 로드합니다. 서비스는 요청별 로컬 상태만 사용하고 `Mat`/ROI/커널/컨투어를 처리 종료 시 모두 해제합니다. 결과는 Java byte 배열과 DTO이므로 호출자가 `Mat.release()`를 호출할 필요가 없습니다. API는 JVM당 동시 이미지 처리 2건을 허용하고 초과 시 HTTP 429를 반환합니다.

## 조정할 값

`ProcessingOptions` 생성자로 조정합니다. API에서는 `ignoreRepeatedPrint`, `protectedTopRows`를 전달할 수 있습니다.

| 옵션 | 기본값 | 의미 |
|---|---:|---|
| `maxDimension` | 2200 | 작업 이미지 최대 변 길이 (800–3000) |
| `protectedTopRows` | 2 | 각 표 상단에서 무조건 보존할 행 수 |
| `ignoreRepeatedPrint` | true | 반복되는 고정 인쇄를 제외할지 여부 |
| `minimumInkRatio` | 0.00035 | 이 값 미만의 유효 잉크 비율이면 빈 행 후보 |
| `minimumComponentArea` | 8 | 정규화된 행에서 남길 연결 성분의 최소 면적 |

빈 행이 남으면 `05-row-decisions.png`와 행별 `inkRatio`를 먼저 확인하세요. 임계값을 올리면 더 많은 행이 제거되며, 작은 필기를 놓칠 가능성도 커집니다. 양식별 기준을 정한 후 사용하는 것이 좋습니다.

## 적용 범위와 검증

이 코드는 범용 문서 인식기가 아닌 OpenCV 기반 휴리스틱 구현입니다. **고정 인쇄와 반복되는 흑백 기입 내용은 픽셀만으로 완전히 구분할 수 없습니다.** 동일한 흑백 값이 많은 행에서 반복되면 고정 인쇄로 오인할 수 있습니다. 중요한 전표는 `report.json`과 행 표시 이미지를 검토하고, 이런 양식에서는 `ignoreRepeatedPrint=false`로 처리하세요. 잉크가 흐리거나 표 선에 겹치면 누락될 수 있고, 도장·배경 얼룩은 빈 행을 유지시키기도 합니다.

원본이 잘리거나 겹친 경우 완전한 문서 외곽 검출은 불가능할 수 있습니다. 겹친 여러 종이는 하나의 평면이 아니므로 표 기반 전역 원근 변환은 근사입니다. 모서리를 확정하지 못하면 문서를 임의로 잘라내지 않고 경고와 함께 보존합니다. 가로·세로 선이 거의 없거나 심하게 휘어진 양식은 별도 대응이 필요합니다.

합성 테스트는 잘못된 입력, 무표 문서 보존, 인쇄 반복 제외, 파란/검은 글씨 보존, 문서 4점 원근 보정, 잘린 문서의 격자 원근 보정 및 HTTP ZIP 응답/400 응답을 검사합니다. 실제 샘플 실행 결과와 정량적 한계는 `VALIDATION.md`에 기록합니다. 실제 사진에는 행별 정답 라벨이 없으므로 처리 성공 수는 판정 정확도를 의미하지 않습니다.

참고: [OpenCV Java Imgproc API](https://docs.opencv.org/4.7.0/javadoc/org/opencv/imgproc/Imgproc.html), [OpenPnP OpenCV](https://github.com/openpnp/opencv), [Spring Boot](https://spring.io/projects/spring-boot).

# 전표 이미지 처리 — Java 21 / OpenCV

문서 외곽·회전·원근 보정, 표 수평·수직선 검출, 반복 인쇄를 제외한 빈 행 제거, 보이는 전표별 종이 분리를 수행합니다. 픽셀 처리는 Java OpenCV만 사용하며 OCR·GPU·외부 이미지 API를 사용하지 않습니다.

## 참고할 문서

| 목적 | 문서 |
|---|---|
| 어떻게 동작하는지, 옵션·결과·한계, 검증·성능 | [동작·검증·성능](docs/PROCESSING.md) |
| Ubuntu에서 Green House 하위 모듈로 설치 | [설치 가이드](docs/MONOREPO_INTEGRATION.md) |
| 설치 후 Bean 등록·기존 업로드 호출·파일 저장 | [Green House 연동 가이드](docs/MIGRATION_GREEN_HOUSE.md) |

설치가 끝났다면 연동 가이드의 **4.3 → 4.4 → 4.5 → 5절** 순서로 진행합니다. 검증·성능 기록은 Windows에서 측정한 결과이며 Ubuntu에서 별도 확인해야 합니다.

## Green House에 설치

이 저장소와 Green House 소스를 Ubuntu에 준비한 뒤, 이 저장소에서 실행합니다.

```bash
bash scripts/install-green-house-module.sh "$HOME/work/green-house"

cd "$HOME/work/green-house/backend"
./gradlew :slip-image-core:test check bootJar --no-daemon
```

스크립트는 코어 소스·테스트 복사와 Gradle·Docker 설정을 적용합니다. 기존 모듈은 덮어쓰지 않습니다. 이후 Bean·업무 호출을 연결하고 관련 변경을 Green House에 함께 커밋합니다. Windows 보조 스크립트는 `scripts/Install-GreenHouseModule.ps1`입니다.

운영 방식은 **Green House 안의 Gradle 하위 모듈**입니다. 코어와 사용하는 코드를 같은 저장소에서 수정·검증하며 별도 패키지 배포나 GitHub Packages 인증은 필요하지 않습니다. 기술 문서 `docs/PROCESSING.md`도 함께 옮겨 유지합니다.

## 저장소 구성

```text
slip-image-core/   이식 대상: 공개 API·제한 실행기·OpenCV 구현·코어 테스트
slip-image-demo/   개발 검증용 Spring Boot 서버·배치·갤러리
scripts/          이식 도구
docs/             동작·검증·성능과 설치·연동 가이드
```

코어는 Spring에 의존하지 않는 일반 JAR입니다. `kr.co.slip.api`가 호출 계약이며 `kr.co.slip.opencv.SlipProcessors.bounded()`를 싱글톤으로 등록합니다. worker·대기열 제한과 native 자원 해제는 코어에 들어 있고, 결과 PNG 저장·응답은 호스트가 담당합니다.

## 이 저장소에서 테스트·데모 실행

JDK 21이 필요합니다. Ubuntu에서는 다음을 실행합니다. Windows 빌드는 `mvnw.cmd`를 사용합니다.

```bash
bash mvnw test package

# 실제 사진은 Git에 포함되지 않으므로 별도로 준비
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar \
  --batch /path/to/photos /path/to/results \
  --workers=1 --queue-capacity=1 --output-mode=REVIEW

# 개발용 HTTP 서버: 기본 127.0.0.1:8080
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar
```

배치 결과는 출력 폴더의 `index.html`에서 비교하고, `summary.json`에서 파일별 결과를 확인합니다. 파일별 수동 회전은 `rotation-overrides.example.json` 형식의 JSON을 배치 출력 경로 다음 인수로 전달합니다.

개발용 HTTP 호출 예시:

```bash
curl -f http://127.0.0.1:8080/api/slips/process \
  -F 'file=@/path/to/photo.jpg' \
  -F 'maxDimension=2200' -F 'rotationDegrees=-1' \
  -F 'outputMode=REVIEW' -o result.zip
```

빈 행을 실제 제거한 결과는 `table-XX-compacted.png`, 전표별 분리 결과는 `paper-XX.png`입니다. `01-corrected.png`는 전체 보정 사진입니다. 자동 정방향·빈 행 판정·종이 분리는 휴리스틱이므로 중요한 전표는 원본과 REVIEW 결과를 비교합니다.

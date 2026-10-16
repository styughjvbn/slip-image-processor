# Green House 전표 이미지 처리 이식 가이드

## 0. Green House에서 사용하려면 여기부터

**설치 스크립트를 이미 실행했다면 이 문서의 4.3절부터 진행하면 됩니다.** 스크립트는 Gradle 의존성 연결까지 수행하며, Spring Bean 등록과 기존 전표 업로드 코드에서의 호출은 아래 절차로 추가합니다.

| 순서 | Green House에서 할 일 | 이 문서의 위치 |
|---|---|---|
| 1 | `SlipImageConfiguration.java` 추가: 실행기를 Spring 싱글톤 Bean으로 등록 | 4.3절 |
| 2 | `DocumentImagePreparationService.java` 추가 또는 기존 application에 처리 코드 통합 | 4.4절 |
| 3 | 기존 업로드 담당 코드에서 서비스를 주입받아 호출 | 4.5절 |
| 4 | 결과 PNG를 기존 저장소에 저장하고 기존 응답에 파일 정보를 연결 | 5절·6절 |
| 5 | Ubuntu에서 테스트·기동·실제 사진 처리 확인 | 9절 |

새 클래스 두 개를 추가하는 경우 위치는 다음과 같습니다. 기존 업로드 유스케이스가 같은 책임을 이미 갖고 있다면 4.4절의 코드를 그 유스케이스에 합쳐도 됩니다.

```text
green-house/backend/src/main/java/com/greenhouse/backend/
└─ sales/document/
   ├─ integration/image/SlipImageConfiguration.java
   └─ application/DocumentImagePreparationService.java
```

호출 흐름:

```text
기존 이미지 업로드 코드
  → DocumentImagePreparationService.prepare(원본 바이트, 회전)
  → Spring에 등록한 이미지 처리 실행기
  → ProcessingResult(PNG 바이트 Map + 판정 보고서)
  → 기존 파일 저장소·기존 응답
```

아직 모듈을 설치하지 않았다면 [Ubuntu 소스 모듈 설치 가이드](MONOREPO_INTEGRATION.md)를 먼저 따릅니다. 성능·메모리 수치는 [PERFORMANCE.md](PERFORMANCE.md), 실제 검증 범위는 [VALIDATION.md](../VALIDATION.md)에서 확인합니다. 이번 연동의 기본 참고 문서는 **현재 문서**입니다.

## 1. 적용 기준과 범위

사용자가 제공한 아키텍처와 `styughjvbn/green-house`의 main 커밋 `6c4232158939b4eabc8511889514bbcef63f76aa`를 기준으로 작성했습니다. 실제 backend는 Java 21·Spring Boot 4.1.0·Gradle Kotlin DSL이며 루트 패키지는 `com.greenhouse.backend`입니다. 아래 업무 연결 코드는 적용 예시이며, 호스트의 `ApiResponse`, `ErrorResponse`, 보안·파일 저장소의 실제 계약에 맞춰 연결해야 합니다. 빌드 연결과 업무 API 통합 검증은 별개입니다.

이 라이브러리는 사진에서 문서 외곽·회전·원근을 보정하고, 표·빈 행·관측 가능한 종이 영역을 검출합니다. OCR, 품목·금액 추출, 판매 전표 생성, 예약·출고·입금·감사 저장은 하지 않습니다. 처리 결과를 판매 사실이나 자동 확정 입력으로 취급하지 않습니다.

**권장 방식은 Green House GitHub 저장소에 `slip-image-core` 소스 모듈을 포함하고 backend가 같은 빌드의 프로젝트 의존성으로 연결하는 것**입니다. [GitHub 저장소 내 모듈 이식 절차](MONOREPO_INTEGRATION.md)를 먼저 읽으세요. 로컬 Maven install이나 사설 패키지 저장소가 전제되지 않습니다. 별도 이미지 서버, 새로운 업무 모듈, Next.js에서의 OpenCV 실행은 필요하지 않습니다. 현재 데모의 ZIP API는 개발 검증용으로 남기고, Green House에서는 기존 Sales의 HTTP·공통 응답·인증 체계를 사용합니다.

## 2. 변경된 저장소 구조

```text
송장 이미지 처리/
├─ pom.xml                         Maven 집계/공통 빌드 설정
├─ slip-image-core/                이식 대상: Java 21 + OpenCV 일반 JAR
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/kr/co/slip/
│     │  ├─ api/
│     │  │  ├─ SlipImageProcessor.java   동기 처리 계약
│     │  │  ├─ ManagedSlipImageProcessor.java  비동기 제출·상태·종료
│     │  │  ├─ ExecutionSettings.java / OutputMode.java
│     │  │  ├─ ProcessingOptions.java    설정·검증
│     │  │  └─ ProcessingResult.java     보고서·PNG 바이트 결과
│     │  ├─ execution/BoundedSlipImageProcessor.java  worker·대기열 제한
│     │  └─ opencv/
│     │     ├─ SlipProcessors.java       제한된 실행기 생성·native 스레드 설정
│     │     ├─ MatScope.java             단계별 native 자원 소유
│     │     ├─ SlipProcessor.java        저수준 동기 처리 엔진
│     │     ├─ PaperSeparator.java       종이 분리 구현
│     │     ├─ PixelScale.java           해상도별 공간 임계값
│     │     └─ NativeOpenCv.java         네이티브 로더
│     └─ test/java/kr/co/slip/           알고리즘·공개 계약 테스트
├─ slip-image-demo/                이식 제외: Spring Boot 실행 예제
│  └─ src/main/
│     ├─ java/kr/co/slip/demo/
│     │  ├─ SlipApplication.java
│     │  ├─ config/SlipImageConfiguration.java
│     │  ├─ web/SlipController.java      개발용 ZIP API
│     │  └─ batch/Batch.java             파일 일괄 처리·갤러리
│     └─ resources/application.yml      데모 전용 포트·업로드 제한
├─ docs/MIGRATION_GREEN_HOUSE.md
├─ raw-data/                       로컬 검증 사진, Git 제외
└─ output/                         로컬 갤러리, Git 제외
```

의존 방향은 `demo → core API ← OpenCV 구현`입니다. 데모의 구성 코드와 CLI 진입점만 구현을 직접 생성합니다. 코어의 런타임 외부 의존성은 OpenPnP OpenCV이며 Spring, HTTP, Jackson, JPA, PostgreSQL에는 의존하지 않습니다. 라이브러리의 `execution` 패키지가 Java 표준 Executor로 실행 한도를 관리하며, `api.ManagedSlipImageProcessor`가 동기·비동기 호출과 종료 계약을 제공합니다. 공개 API의 중첩 값과 generic에도 `Mat`, `MultipartFile`, HTTP 응답이나 Entity를 노출하지 않습니다.

단순 전달을 위한 application 서비스·Adapter를 라이브러리 내부에 추가하지 않았습니다. `SlipImageProcessor`는 OpenCV 기술 구현을 호스트의 업무 코드에서 격리하는 실제 경계이며, 기존 엔진이 이 계약을 직접 구현합니다. 실제 소비자가 없는 SPI·Repository·domain 패키지는 만들지 않습니다.

## 3. Green House 내 소유 위치

판매 전표 사진을 준비하는 현재 용도라면 `sales/document`가 완료 책임을 갖도록 배치합니다. 기존의 `document/direct/auction/payment/partner` 의존 방향과 `sales/api/document`의 업무 계약은 유지합니다.

```text
green-house/backend/src/main/java/<실제 루트 패키지>/
└─ sales/document/
   ├─ application/
   │  └─ DocumentImagePreparationService.java   업무 조율·출력 정책
   ├─ integration/image/
   │  └─ SlipImageConfiguration.java            외부 기술 구현 Bean 구성
   └─ web/
      ├─ ...기존 전표 Controller 또는 이미지 전용 Controller
      └─ dto/...                              필요할 때만 HTTP 전용 값
```

- `common`에 OpenCV나 판매 이미지 업무를 넣지 않습니다. `print`는 문서 출력 책임이므로 사진 전처리의 소유자로 삼지 않습니다.
- 새 최상위 업무 모듈이나 `ai` 모듈을 추가하지 않습니다. 첨부 문서에는 `ai`가 의존 표에만 등장하므로 실제 모듈 존재·소유권을 추정해 사용하지 않습니다.
- 다른 모듈에서 이미지 처리 업무를 실제로 소비하게 될 때만 소유 모듈의 API/SPI와 기존 허용 의존 방향을 검토합니다. 지금부터 Farm·Work가 Sales 구현을 호출하게 만들지 않습니다.
- 파일 소유권·조회 권한·전표 연결은 Sales가 결정합니다. 이 라이브러리가 Farm Entity, Sales Repository, Payment 원장 또는 Audit를 호출하지 않습니다.
- 기존 `ModularArchitectureTests`와 `ModuleBoundaryInventoryTest`가 검토하는 정확한 타입·메서드 목록을 확인합니다. 외부 라이브러리 추가 때문에 `sales..`나 `integration..` 전체를 검사 예외로 만들지 않습니다.

## 4. 권장 이식: GitHub 저장소에 소스 모듈 포함

### 4.1 복사할 코어

`slip-image-core/src`와 사용하는 빌드 파일을 Green House의 `backend/modules/slip-image-core` 같은 경로에 복사하고 Git에 함께 커밋합니다. `pom.xml`은 이 저장소 부모 POM 없이 독립 빌드할 수 있고, `build.gradle.kts`도 제공됩니다. 데모 서버·사진·출력·빌드 산출물은 복사하지 않습니다.

### 4.2 같은 빌드에서 연결

Gradle은 `settings.gradle(.kts)`에 코어 경로를 include하고 앱이 `implementation(project(":slip-image-core"))`로 참조합니다. Maven은 집계 POM의 modules에 코어와 backend를 포함하고 backend가 코어 좌표에 의존하도록 구성합니다. `jar`인 기존 앱 POM을 무조건 집계 POM으로 바꾸지 않습니다.

실제 settings/POM, CI 및 Docker의 빌드 진입점에 맞춘 예시는 [저장소 내 이식 가이드](MONOREPO_INTEGRATION.md)에 있습니다. backend의 기존 Spring Boot 버전은 유지하고 기술 라이브러리에는 Boot plugin을 적용하지 않습니다. OpenPnP OpenCV는 코어의 런타임 의존성으로 함께 들어옵니다.

`mvn install`로 로컬 저장소에 설치하는 방식은 별도 개발 확인 용도로는 쓸 수 있지만 같은 PC에만 적용됩니다. 이번 GitHub 소스 포함 방식에서는 개발자·CI 모두 reactor/project 의존성으로 코어를 함께 빌드하므로 install이나 사설 패키지 배포가 필요하지 않습니다. 여러 프로젝트에 독립 버전으로 배포해야 하는 요구가 생기면 원격 아티팩트 저장소를 별도로 검토합니다.

### 4.3 호스트가 Bean 등록

Green House에 `backend/src/main/java/com/greenhouse/backend/sales/document/integration/image/SlipImageConfiguration.java`를 추가합니다. 아래 코드는 그대로 등록할 수 있는 구성 예시이며 기본 worker 2개·대기열 4개·OpenCV 내부 스레드 1개를 사용합니다.

```java
package com.greenhouse.backend.sales.document.integration.image;

import java.time.Duration;
import kr.co.slip.api.ExecutionSettings;
import kr.co.slip.api.ManagedSlipImageProcessor;
import kr.co.slip.opencv.SlipProcessors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SlipImageConfiguration {
    @Bean(destroyMethod = "close")
    public ManagedSlipImageProcessor slipImageProcessor(
            @Value("${sales.document.image.workers:2}") int workers,
            @Value("${sales.document.image.queue-capacity:4}") int queueCapacity,
            @Value("${sales.document.image.opencv-threads:1}") int openCvThreads) {
        return SlipProcessors.bounded(
                new ExecutionSettings(workers, queueCapacity, Duration.ofSeconds(30)), openCvThreads);
    }
}
```

이 클래스는 호스트의 기존 component scan 아래에 둡니다. `kr.co.slip` 전체를 추가 스캔하거나 데모의 `SlipApplication`을 import하지 않습니다. 코어에는 자동 구성이나 `@Service`가 없습니다. **실행 제한은 이식용 core JAR 안에 구현돼 있으며** 호스트는 설정과 Bean 생명주기만 연결합니다. application은 공개 API만 주입받고 OpenCV 구현을 직접 생성하지 않습니다.

`SlipProcessors.bounded()`는 OpenCV 로딩과 JVM 전체의 native 스레드 설정을 초기화합니다. 기본 OpenCV 스레드는 1, worker는 2, 대기열은 4입니다. 여러 인스턴스를 만들면 worker/대기열 한도는 각각 따로 생깁니다. 동시 처리 한도를 공유하려면 **호스트의 모든 이미지 입력 경로가 같은 싱글톤을 사용**해야 합니다. 낮은 수준의 `new SlipProcessor()` 호출은 제한을 우회하므로 직접 사용하지 않습니다.

OpenCV 스레드 설정은 JVM 전체에 영향을 줍니다. 이 라이브러리의 factory에 서로 다른 값을 지정하면 예외를 발생시킵니다. 다른 OpenCV 사용처도 있다면 초기화 정책을 함께 정하고, 처리 중 `Core.setNumThreads()`를 바꾸지 마세요. 이 설정은 이미지 worker 수를 대신하지 않습니다. 네이티브 파일이 맞지 않으면 Bean 생성 시 기동이 실패할 수 있습니다.

### 4.4 application에서 사용

아래 서비스는 출력 정책과 설정을 소유합니다. 이미 전표 준비 유스케이스가 있다면 해당 application에 합치고 별도 전달 서비스를 만들 필요는 없습니다. 호스트에 별도 Executor나 Semaphore를 복제하지 않습니다.

새 서비스로 추가한다면 위치는 `backend/src/main/java/com/greenhouse/backend/sales/document/application/DocumentImagePreparationService.java`입니다.

```java
package com.greenhouse.backend.sales.document.application;

import kr.co.slip.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentImagePreparationService {
    private final SlipImageProcessor processor;
    private final ProcessingOptions defaults;

    public DocumentImagePreparationService(SlipImageProcessor processor,
            @Value("${sales.document.image.max-dimension:2200}") int maxDimension) {
        this.processor = processor;
        this.defaults = ProcessingOptions.defaults().withMaxDimension(maxDimension)
                .withOutputMode(OutputMode.PRODUCTION);
    }

    public ProcessingResult prepare(byte[] original, int rotationDegrees) {
        return processor.process(original, defaults.withRotation(rotationDegrees));
    }
}
```

`process()`는 같은 실행기에 제출한 뒤 결과를 기다리는 동기 호출입니다. application 값과 HTTP DTO를 중복 생성할 필요는 없습니다. `ProcessingResult`는 기능 내부의 기술 결과로 사용하고, 다른 최상위 모듈에는 실제 필요한 문서 소유 값만 공개합니다. 코어 입력 오류와 `ProcessingRejectedException`은 기존 호스트 예외 체계에 기능 단위로 연결합니다.

다중 파일의 비동기 처리에는 `ManagedSlipImageProcessor`를 주입받습니다.

```java
var future = processor.processAsync(() -> java.nio.file.Files.readAllBytes(path), options);
// loader는 worker가 확보된 뒤 실행됩니다. 대기열에는 파일 경로만 남습니다.
// future의 완료 결과를 저장/소비하고 참조를 해제합니다.
```

대기열 한도를 넘긴 제출은 즉시 `ProcessingRejectedException`을 던집니다. queue-capacity=0은 대기 없이 worker가 비어 있을 때만 접수합니다. 무제한 재시도나 전체 파일의 바이트/결과를 List에 쌓지 않습니다. loader에는 처리 직전에 읽을 수 있는 안정적인 파일을 사용하고 HTTP 요청이 끝나면 사라지는 `MultipartFile` 임시 파일을 그대로 참조하지 마세요. `byte[]` 비동기 인수를 사용하면 대기 중에도 그 바이트가 유지되며 호출자는 내용을 변경하면 안 됩니다.

취소는 **대기 중인 작업을 제거하거나 결과 대기를 중단**하는 의미입니다. 실행 중인 JNI를 즉시 중단하지 않습니다. 해당 native 작업이 실제 끝날 때까지 worker 한도를 계속 점유합니다. 같은 실행기의 loader 안에서 동기 `process()`를 재호출하면 deadlock을 막기 위해 거부합니다. CompletableFuture callback은 완료 worker에서 실행될 수 있으므로 callback에서 같은 실행기에 동기 호출하거나 `close()`하지 않습니다.

`close()`는 신규 접수를 막고 기존 작업 종료를 설정 시간까지 기다립니다. 시간 초과 시 대기 작업의 Future를 실패시키고 실행 중 스레드에 interrupt를 요청하지만, native 연산 종료를 보장하지 않으며 예외로 알립니다. 호스트 종료 유예 시간을 가장 긴 처리 시간과 대기열에 맞추고, 종료는 worker 밖에서 수행합니다. `status()`의 active/queued/completed/rejected 값은 관측용 순간값이며 admission 제어를 대신하지 않습니다. 완료 결과·HTTP ZIP 조립·DB/파일 저장 메모리는 소비자가 관리합니다.

설정 예시(호스트의 기존 YAML에 병합):

```yaml
sales:
  document:
    image:
      max-dimension: 2200
      workers: 2
      queue-capacity: 4
      opencv-threads: 1
```

요청별 크기 선택이 필요하면 HTTP 계약에서 800–3000을 검증하고 `defaults.withMaxDimension(requested)`를 사용합니다. 위 예시는 서버 설정으로 크기를 고정하고 회전만 호출 인수로 받습니다.

### 4.5 기존 업로드 코드에서 호출

기존 업로드를 담당하는 Controller 또는 application 클래스의 **생성자 인수에 `DocumentImagePreparationService`를 추가**하고 필드로 보관합니다. 기존 생성자와 의존성은 유지합니다.

```java
// 기존 업로드 담당 클래스의 필드
private final DocumentImagePreparationService imagePreparationService;

// 기존 생성자에서 주입받은 인수를 보관
// this.imagePreparationService = imagePreparationService;
```

업로드받은 `MultipartFile file`을 처리하는 기존 메서드 안에서는 다음처럼 호출합니다. `imagePreparationService`는 생성자 주입한 필드이며, 새 Controller나 endpoint를 가정한 코드는 아닙니다.

```java
ProcessingResult result = imagePreparationService.prepare(file.getBytes(), -1);

// 전체 문서의 회전·원근을 보정한 PNG
byte[] correctedPng = result.images().get("01-corrected.png");

// 표마다 빈 행을 제거한 PNG: 검출된 표 개수에 따라 존재
var compactedTables = result.images().entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("table-")
                && entry.getKey().endsWith("-compacted.png"))
        .toList();

// 검출된 전표별 PNG와 판정 보고서는 result.images()/report()에서 사용
var report = result.report();
```

필요한 import는 `kr.co.slip.api.ProcessingResult`와 `com.greenhouse.backend.sales.document.application.DocumentImagePreparationService`입니다. 회전 인수 `-1`은 자동, `0/90/180/270`은 시계 방향 수동 회전입니다.

여기서 기존 파일 저장 코드에 `correctedPng` 또는 선택한 표/종이 PNG 바이트를 전달하고, 저장 후 반환된 파일 ID·URL을 기존 응답에 연결합니다. **빈 행을 제거한 결과가 필요하면 `01-corrected.png`가 아니라 `table-XX-compacted.png`를 사용**합니다. 종이를 각각 분리한 결과는 `paper-XX.png`입니다. 해당 표·종이가 검출되지 않았다면 그 파일은 없으므로 고정 파일명 하나의 존재를 전제하지 않습니다.

원본 파일과 결과 파일은 각각의 저장 정책을 정하고 처리 결과로 원본을 덮어쓰지 않습니다. 처리·저장·응답이 연결되기 전에는 이 라이브러리를 추가한 것만으로 기존 화면이나 전표 생성 동작이 바뀌지 않습니다.

## 5. HTTP·저장·트랜잭션 연결

### 호스트의 기존 계약 유지

데모의 `/api/slips/process`, `ProblemDetail`, `ResponseEntity<byte[]>` ZIP 응답과 localhost 포트 설정을 그대로 이식하지 않습니다. 기존 Sales의 인증·인가·ROLE_DEMO 제한, `ApiResponse`/`ErrorResponse`, 공통 filter 실패 writer를 유지합니다.

새 endpoint·파일 저장 계약은 이번 소스 모듈 연결 범위에서 확정하지 않습니다. 기본 연결 순서는 다음과 같습니다.

1. Controller에서 인증·인가, multipart 크기·파라미터 검증을 수행합니다. 기존 전표에 연결한다면 이미지 처리 전에 접근 권한을 확인합니다.
2. 원본 바이트와 회전 옵션을 Document application에 전달합니다. `MultipartFile`을 코어 API나 다른 모듈의 공개 계약에 전달하지 않습니다.
3. DB 트랜잭션 밖에서 전처리합니다. 검출 결과와 경고를 사용자에게 검토 가능한 초안으로 취급합니다.
4. 파일을 보관한다면 호스트의 저장 기능을 사용해 원본과 필요한 결과를 저장하고 파일 식별자·메타데이터를 조립합니다. 경로를 클라이언트 입력 그대로 사용하지 않습니다.
5. 기존 전표에 연결하는 DB 쓰기는 Document 소유의 별도 짧은 최상위 application 트랜잭션에서 권한·전표 상태를 다시 확인한 뒤 처리하고, 해당 변경의 감사 기록을 같은 트랜잭션으로 저장합니다. 처리 시작과 저장 사이의 상태 변경을 무시하지 않습니다.
6. 성공은 기존 공통 envelope 안에 파일 식별자·권한 확인된 조회 URL·필요한 처리 메타데이터를 담습니다. PNG 바이트 배열 전체를 JSON/base64 응답으로 복제하지 않습니다. 이미지/다운로드 endpoint가 필요하면 기존 파일 응답 정책을 따릅니다.

파일 저장과 DB 커밋은 일반적으로 하나의 ACID 트랜잭션이 아닙니다. 임시 저장→DB 연결→실패 시 정리 등 기존 파일 처리 정책을 따르세요. 이 라이브러리는 저장·멱등 키·감사 이벤트를 구현하지 않으며 DB migration도 제공하지 않습니다. 저장 기능을 새로 구현할 경우에만 소유권·실패 정리·기존 Flyway 순서를 설계합니다.

긴 이미지 연산을 `@Transactional` 메서드 안에서 실행하거나 Controller에 새 트랜잭션을 열지 않습니다. 이미 열린 트랜잭션을 단순히 일시 중단해도 기존 잠금이 해제되는 것은 아니므로, 호출 순서를 분리합니다. 이 처리 경로에서 Farm Mutation, 예약, 입금 상태를 변경하지 않습니다.

### 오류 매핑

아래 코드는 **호스트에 등록할 안정적인 오류 코드 제안**입니다. 기존 오류 enum/handler에 기능 단위로 연결하며 예외 메시지 문자열을 프론트에서 해석하지 않습니다.

| 조건 | HTTP | 제안 코드 / 연결 위치 |
|---|---:|---|
| 빈 파일·디코딩 실패·잘못된 옵션·허용 픽셀 초과 | 400 | `DOCUMENT_IMAGE_INVALID_INPUT`, 코어 IllegalArgumentException을 기능 단위 오류로 변환 |
| 프레임워크 multipart 업로드 제한 초과 | 413 | 기존 업로드 공통 오류; 처리 엔진에 도달하기 전에 발생 |
| 동시 처리 한도 초과 | 429 | `DOCUMENT_IMAGE_PROCESSING_BUSY`, core API의 ProcessingRejectedException |
| 표·종이 검출 불확실, 자동 회전 검토 필요 | 200 | 실패로 바꾸지 않고 `warnings`, `reviewRequired` 전달 |
| 예상하지 못한 OpenCV 처리 오류 | 500 | `DOCUMENT_IMAGE_PROCESSING_FAILED`, 서버 원인 로그·공통 오류 응답 |
| 호출자 대기 중단 | 호스트 정책 | `ProcessingInterruptedException`; native 작업은 즉시 중단되지 않을 수 있음 |
| 네이티브 로더 실패 | 기동 실패 | 배포 진단 대상; 정상 처리 결과로 숨기지 않음 |

핵심 입력 한도는 **20 MiB**, 디코딩된 이미지 **60,000,000 픽셀**입니다. 기존 multipart 설정이 더 작다면 코어에 도달하기 전에 제한됩니다. Spring/프록시의 업로드 제한을 검토해 의도한 크기와 맞추되 기존 전역 제한을 무조건 늘리지 않습니다. `nu.pattern.OpenCV.loadLocally()`는 네이티브 파일을 임시 경로에 추출하므로 쓰기 가능하고 네이티브 로딩이 허용된 임시 경로가 필요합니다.

## 6. 처리 옵션과 결과 계약

출력 모드는 `withOutputMode(OutputMode.PRODUCTION/REVIEW/ALL)`로 선택합니다. 기존 호출과 파일명을 보존하려고 기본값은 ALL을 유지했습니다. 운영에서는 PRODUCTION을 명시적으로 사용하세요.

| 모드 | 생성 파일 |
|---|---|
| PRODUCTION | 01-corrected + 실제 검출된 표/종이 PNG + 보고서 |
| REVIEW | PRODUCTION + 05-row-decisions + 06-paper-contours |
| ALL | REVIEW + 02-lines + 03-horizontal + 04-vertical + 07-paper-mask |

미선택 overlay는 복제·그리기·인코딩부터 생략합니다. 보고서·표/종이 판정과 공통 출력 PNG는 모드에 따라 바뀌지 않습니다. CLI는 `--workers=2 --queue-capacity=4 --opencv-threads=1 --output-mode=PRODUCTION`으로 같은 library 실행기를 사용하며 완료된 결과부터 저장합니다. 완료 순서는 달라도 summary/갤러리는 파일명 순으로 정리합니다. 병렬 배치의 milliseconds는 대기와 저장 시간을 포함하므로 순수 엔진 벤치마크로 사용하지 않습니다.


```java
ProcessingOptions options = ProcessingOptions.defaults()
        .withMaxDimension(1600)
        .withRotation(-1); // -1=AUTO, 또는 EXIF 반영 후 추가 시계 방향 0/90/180/270도

ProcessingResult result = processor.process(originalBytes, options);
byte[] corrected = result.images().get("01-corrected.png");
var papers = result.report().papers();
var tables = result.report().tables();
// paper-01.png 등은 실제 검출됐을 때만 존재합니다.
```

| 옵션 | 기본값 | 의미 |
|---|---:|---|
| maxDimension | 2200 | 입력 축소 시 긴 변 제한, 800–3000; 작은 입력은 확대하지 않음 |
| protectedTopRows | 2 | 표의 상단 보존 행 수, 1–10 |
| ignoreRepeatedPrint | true | 반복 인쇄 패턴 제외 추정 |
| minimumInkRatio | 0.00035 | 유효 잉크 비율의 빈 행 판정 기준, 0–0.1 |
| minimumComponentArea | 8 | 기본 크기 기준 최소 연결 성분 면적, 2–1000 |
| rotationDegrees | -1 | 자동 또는 0/90/180/270 |

전체 옵션은 `new ProcessingOptions(maxDimension, protectedTopRows, ignoreRepeatedPrint, minimumInkRatio, minimumComponentArea, rotationDegrees)`로 지정할 수 있습니다.

`min(원본 긴 변, maxDimension) / min(원본 긴 변, 2200)`을 공간 배율로 사용합니다. 거리·커널·행 정규화 높이는 배율, 최소 연결 성분 면적은 배율²를 적용합니다. 이미지 기반 비율·각도·색상·잉크 비율은 유지합니다. 내부 최대 900px 종이 분석은 해당 좌표계 배율을 별도로 적용합니다. 저해상도의 정보 손실로 크기별 빈 행 판정이 달라질 수 있습니다.

반환값은 일반 Java record와 PNG `byte[]`이며 native `Mat`은 호출 종료 시 코어가 해제합니다. 호출자가 `Mat.release()`를 호출할 필요는 없습니다. 다만 `byte[]`까지 깊은 불변 값은 아니므로 공유된 결과 배열을 수정하지 마세요. 결과를 장기간 메모리 캐시에 보관하면 PNG·중간 결과 용량이 누적됩니다.

- `01-corrected.png`: 전체 보정 영상. 빈 행을 실제 잘라내지 않습니다.
- `table-XX-compacted.png`: 해당 표에서 빈 행을 실제 제거한 영상.
- `paper-XX.png`: 배경이 투명한 RGBA 전표별 영상. 가려진 부분은 복원하지 않습니다.
- `05-row-decisions.png`, `06-paper-contours.png`, `07-paper-mask.png`: 판정 검토용 영상.
- 표/행/종이 좌표: 최종 보정 영상 기준. 종이 PNG의 좌상단은 해당 `bounds.x/y`입니다.
- `documentCorners`: 검출 성공 시 EXIF 반영 후 원본 영상 좌표. 최종 영상 좌표와 다릅니다.
- `rotation.reviewRequired`, `papers[].reviewRequired`, `warnings`: 검토 필요 정보. 문서 승인·판매 상태 capability와 혼합하지 않습니다.

## 7. 소스 복사로 이식해야 할 때

소스 모듈을 별도로 두기 어려워 feature 패키지 안에 직접 합쳐야 할 때만 선택합니다. 권장 소스 모듈 방식과 구분합니다. **코어의 main 소스 전체와 test 소스만** 복사하고 데모·루트 실행 클래스·application.yml·부모 POM은 복사하지 않습니다.

| 원본 패키지 | 대상 패키지 예시 |
|---|---|
| `kr.co.slip.api` | `<base>.sales.document.api.image` |
| `kr.co.slip.execution` | `<base>.sales.document.integration.image.execution` |
| `kr.co.slip.opencv` | `<base>.sales.document.integration.image.opencv` |

`package`와 일반/static `import`를 함께 변경하고 테스트도 같은 대응 패키지로 옮깁니다. `PublicApiBoundaryTest`의 허용 API 패키지 문자열도 변경합니다. `PixelScale`과 `PaperSeparator`는 package-private이므로 OpenCV 구현 파일은 같은 패키지에 둡니다. `SlipProcessors` factory의 execution import도 바꾸고 제한된 실행기와 `@Bean(destroyMethod="close")` 구성을 유지합니다.

호스트 빌드에 `org.openpnp:opencv:4.9.0-0` 의존성 하나를 추가하고 JUnit 테스트는 기존 테스트 도구를 사용합니다. feature API를 최상위 모듈 외부에 자동 공개하지 않습니다. 다른 최상위 모듈이 실제로 사용하는 업무 계약만 `sales/api/document`에 정의하고 기존 정확한 타입/메서드 검사를 갱신합니다.

이 방식은 호스트가 알고리즘 소스·수정 이력을 직접 소유하게 됩니다. 버전 업데이트를 받기 위해서는 소스 차이를 직접 병합해야 합니다. 라이브러리 방식과 소스 복사를 동시에 사용하면 같은 엔진이 중복되므로 한 방식을 선택합니다.

## 8. Frontend·OpenAPI 연결

전처리를 기존 판매 전표 업로드 흐름에 추가한다면 기존 frontend 기능 안에서 HTTP 계약을 연결합니다. 이미지 정방향 확인·분리 결과 선택 같은 입력 상태는 해당 화면의 UI state로 두고, 저장된 문서 상세와 목록은 기존 TanStack Query/URL 정책을 유지합니다. 처리 결과를 별도 local state에 영구 복제해 서버 문서 상태로 취급하지 않습니다.

Controller·요청/응답 계약·테스트를 먼저 변경한 뒤 Green House 루트에서 기존 생성 절차를 실행합니다.

```text
python3 scripts/generate_openapi.py
cd frontend
npm run api:types
```

OpenAPI나 생성 TypeScript schema를 직접 수정하지 않습니다. 이 저장소에는 해당 스크립트·frontend가 없으므로 여기서는 실행하지 않았습니다. 기존 판매 상세나 첨부파일이 실제로 변경됐을 때 관련 query를 무효화하며, 전처리만 수행하고 저장하지 않은 단계에서 판매·재고·입금 cache를 변경할 필요는 없습니다.

## 9. 배포와 검증

Java 21, 호스트 OS/CPU에 맞는 OpenCV 네이티브 라이브러리와 추출 경로가 필요합니다. 포함된 OpenPnP 버전이 실제 이미지의 OS/아키텍처를 지원하는지 확인하세요. 현재 검증 환경은 Windows x64이며 Linux 컨테이너·미니 PC·Kubernetes namespace의 실제 기동은 이 저장소에서 검증하지 않았습니다. glibc 기반 Java 21 이미지에서 먼저 검증하고 Alpine/musl이나 ARM에서 동작을 추정하지 않습니다. 다른 버전의 시스템 OpenCV를 중복 로드하지 않습니다.

권장 검증 순서:

1. Ubuntu의 Green House `backend`에서 아래 테스트·패키징 명령 실행. 앱과 코어가 함께 빌드되는지 확인.
2. backend의 기존 빌드로 코어 의존성 해석과 `SlipImageProcessor` Bean 등록 확인. 호스트 Boot/JDK 버전은 유지.
3. 호스트에서 실제 이미지 한 장의 원근·표·종이 PNG와 경고를 확인. 800/1100/1600/2200/3000 해상도와 수동 회전, 검출 실패 시 원본 유지도 검사.
4. 기존 `ModularArchitectureTests`, `ModuleBoundaryInventoryTest` 실행. 공개 중첩 타입·generic·정확한 메서드 호출·구현 접근 위치까지 확인.
5. 공통 envelope·오류 코드, 인증·전표 접근권한·데모 제한, 업로드 제한과 429 처리 확인. 실제 저장을 추가했다면 저장 실패·감사 rollback·중복 요청·파일 정리를 별도 검증.
6. 기존 OpenAPI/TypeScript 생성·frontend 검사 실행.
7. 실제 Docker/배포 이미지에서 native 로딩, 임시 경로, 업로드 한도, 동시 실행 1~2건의 RSS·native 메모리·응답 시간을 확인. `-Xmx`는 native Mat 메모리까지 제한하지 않습니다.

```bash
cd /실제/green-house/경로/backend
./gradlew :slip-image-core:test check bootJar --no-daemon
# 이후 기존 Green House 개발 환경 설정으로 기동
APP_ENV=dev ./gradlew bootRun
```

빌드 성공 후 기존 업로드 경로로 사진 한 장을 보내고, 결과 PNG가 실제 저장·조회되는지 확인해야 업무 연동까지 완료됩니다. 코어 테스트 통과는 호스트의 업로드·저장·권한 계약 검증을 대신하지 않습니다.

기존 알고리즘의 한계는 유지됩니다. 3장 겹침은 일반화 검증되지 않았고, 세로 경계로 좌우 겹친 종이를 분할하는 로직은 없습니다. 회전 자동 판정, 반복 인쇄와 반복 필기의 구분, 흐린 잉크에는 검토가 필요합니다. 이번 구조 변경은 이 검출 범위를 확대하지 않습니다.

## 10. 기존 사용자 변경 사항

| 이전 | 변경 후 |
|---|---|
| `kr.co.slip.ProcessingOptions/ProcessingResult` | `kr.co.slip.api.*` |
| `kr.co.slip.SlipProcessor` | `kr.co.slip.opencv.SlipProcessor`; 소비 코드는 `SlipImageProcessor` 사용 |
| 자동 스캔되는 `@Service SlipProcessor` | 호스트가 제한된 factory 결과를 `@Bean(destroyMethod="close")`으로 등록 |
| 루트 `src/main`, `src/test` | core와 demo 각 모듈로 이동 |
| `target/slip-image-processor-1.0.0.jar` | `slip-image-demo/target/slip-image-processor-1.0.0.jar` |
| 이식용 라이브러리 없음 | `slip-image-core/target/slip-image-core-1.0.0.jar` |

데모의 ZIP 형식, 파일명, 회전·크기 옵션, 배치 인수와 기존 갤러리는 유지합니다. 원본 사진을 바꾸거나 기존 판매·재고 DB를 수정하는 이식 스크립트는 없습니다.

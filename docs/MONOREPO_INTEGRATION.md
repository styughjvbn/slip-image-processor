# Ubuntu에서 Green House에 코어 소스 모듈 이식

## 기본 방식

**코어 소스와 Gradle 빌드 설정을 Green House 저장소에 포함하고, Ubuntu 개발 환경에서 빌드합니다.** Windows에서 빌드한 JAR을 복사하는 절차가 아닙니다. 개발자와 CI는 같은 Git 저장소의 소스를 함께 빌드하므로 로컬 Maven install이나 사설 패키지 저장소가 필요하지 않습니다. OpenCV 등 공개 외부 의존성은 Maven Central에서 받습니다.

확인 대상은 [styughjvbn/green-house](https://github.com/styughjvbn/green-house)의 `main`, 커밋 `6c4232158939b4eabc8511889514bbcef63f76aa`입니다. 당시 backend는 Java 21, Spring Boot 4.1.0, Gradle Wrapper 9.5.1, Kotlin DSL을 사용하며, 앱은 backend 루트 프로젝트입니다. 아래 설정은 기존 파일에 병합합니다. 이후 저장소 구조가 바뀌었다면 현재 파일과 비교해 적용하세요.

## 1. Ubuntu 작업 경로와 준비

Ubuntu에 JDK 21과 Git을 준비합니다. 시스템 Gradle 대신 Green House에 포함된 Gradle Wrapper를 사용합니다.

```bash
java -version
javac -version
```

두 명령이 모두 Java 21을 사용하는지 확인합니다. 여러 JDK가 설치돼 있으면 `JAVA_HOME`과 `PATH`도 JDK 21을 가리키도록 맞춥니다. 첫 빌드는 Gradle 배포본·플러그인·공개 의존성을 다운로드하므로 네트워크 연결이 필요합니다.

이 가이드의 예시 경로는 다음과 같습니다. 실제 위치에 맞게 변수 두 개를 변경하세요. 이미지 처리 저장소의 최신 코어 소스가 Ubuntu에서도 준비돼 있어야 합니다.

```bash
SLIP_REPO="$HOME/work/slip-image-processing"  # 이 이미지 처리 저장소
GREEN_HOUSE_REPO="$HOME/work/green-house"   # Green House 저장소
```

Green House가 아직 없다면 원하는 경로에 clone합니다. 기존 checkout이 있다면 그대로 사용합니다.

```bash
git clone https://github.com/styughjvbn/green-house.git "$GREEN_HOUSE_REPO"
```

작업 전 호스트 `AGENTS.md`를 읽고 현재 변경 사항을 확인합니다. 작업 브랜치는 호스트의 브랜치 규칙에 따라 준비합니다.

```bash
git -C "$GREEN_HOUSE_REPO" status --short
```

### 권장: Ubuntu 이식 스크립트로 적용

아래 명령은 소스·테스트·코어 Gradle 파일을 복사하고, 이 문서 3절과 4절의 Gradle·Docker 변경도 적용합니다. 이 이미지 처리 저장소에서 실행합니다.

```bash
cd "$SLIP_REPO"
bash scripts/install-green-house-module.sh "$GREEN_HOUSE_REPO"
```

기존 모듈이 있거나 예상한 빌드 구조와 다르면 중단합니다. 자동 적용했다면 2~4절은 변경 검토용으로 읽고 같은 설정을 중복 추가하지 않습니다. 이어서 5절의 빌드 검증과 6절의 Bean·업무 연결을 진행합니다. 스크립트는 commit/push나 Bean 등록을 수행하지 않습니다.

## 2. 복사·커밋할 범위

복사 후 구조는 다음과 같습니다.

```text
green-house/
└─ backend/
   └─ modules/
      └─ slip-image-core/
         ├─ src/main/          API·실행 제어·OpenCV 구현
         ├─ src/test/          코어 회귀 테스트
         └─ build.gradle.kts   Gradle Java library 빌드
```

**`slip-image-core/src` 전체와 `slip-image-core/build.gradle.kts`만 복사합니다.** Green House는 Gradle을 사용하므로 `pom.xml`은 필요하지 않습니다. `target`, `build`, `.gradle`, `.tools`, 원본 사진, 출력 PNG, `slip-image-demo`, 이 저장소 루트 빌드 설정은 복사하지 않습니다.

Ubuntu Bash에서 다음을 실행합니다. 기존 모듈이 있으면 덮어쓰지 않고 중단합니다. 기존 모듈 업데이트는 변경 내용을 비교해 별도로 진행하세요.

```bash
(
    set -eu
    test -d "$SLIP_REPO/slip-image-core/src"
    test -f "$SLIP_REPO/slip-image-core/build.gradle.kts"
    MODULE_DIR="$GREEN_HOUSE_REPO/backend/modules/slip-image-core"
    if [ -e "$MODULE_DIR" ] || [ -L "$MODULE_DIR" ]; then
        echo "이미 모듈이 있습니다. 기존 내용을 먼저 확인하세요: $MODULE_DIR" >&2
        exit 1
    fi
    mkdir -p "$MODULE_DIR"
    cp -R "$SLIP_REPO/slip-image-core/src" "$MODULE_DIR/"
    cp "$SLIP_REPO/slip-image-core/build.gradle.kts" "$MODULE_DIR/"
)
```

`kr.co.slip.*` 패키지는 그대로 유지합니다. 코어는 같은 프로세스에 포함되는 기술 라이브러리이며 Spring에 의존하지 않습니다. 코어 Gradle 설정은 `java-library`만 적용합니다. backend의 기존 Spring Boot 버전을 유지하고 코어에 Boot plugin을 적용하지 않습니다.

자동 스크립트 대신 직접 적용하려면 위 복사 명령과 아래 수동 설정을 사용합니다. `scripts/Install-GreenHouseModule.ps1`은 Windows용 보조 도구입니다.

## 3. Green House Gradle 설정 연결

### 3.1 backend/settings.gradle.kts

기존 설정에 추가합니다.

```kotlin
include(":slip-image-core")
project(":slip-image-core").projectDir = file("modules/slip-image-core")
```

### 3.2 backend/build.gradle.kts

기존 `dependencies` 블록 안에 프로젝트 의존성을 추가합니다.

```kotlin
dependencies {
    // 기존 의존성 유지
    implementation(project(":slip-image-core"))
}
```

이번 연결에는 `implementation("kr.co.slip:slip-image-core:1.0.0")`나 `mavenLocal()`을 추가하지 않습니다. 같은 빌드에 포함된 프로젝트를 참조하므로 로컬 Maven 설치 없이 동작합니다.

확인한 Green House의 `workE2eTest` 블록에는 다음 설정이 있습니다.

```kotlin
systemProperty("greenhouse.cli.runtime-classpath", sourceSets["main"].runtimeClasspath.asPath)
```

**위 한 줄을 다음 블록으로 교체**합니다. 기존 한 줄을 바깥에 남겨두지 않습니다.

```kotlin
doFirst {
    systemProperty("greenhouse.cli.runtime-classpath", sourceSets["main"].runtimeClasspath.asPath)
}
```

클래스 경로 값은 유지하고 평가 시점을 테스트 실행 직전으로 늦춥니다. 구성 단계에서 의존성을 먼저 해석하면 하위 모듈 구성이 끝나기 전에 프로젝트 의존성 해석이 실패할 수 있어 필요한 변경입니다. 이미 지연 평가하도록 바뀌었다면 중복 적용하지 않습니다.

호스트가 이후 `FAIL_ON_PROJECT_REPOS` 정책으로 저장소를 중앙 관리하게 됐다면 코어의 `repositories` 블록을 제거하고 호스트 중앙 설정의 `mavenCentral()`을 사용합니다. 코어를 연결하기 위해 호스트 저장소 정책을 낮추지 않습니다.

## 4. Docker 빌드 설정

`backend/Dockerfile`의 소스 복사 부분에 모듈 복사를 추가합니다.

```dockerfile
COPY modules modules
COPY src src
RUN ./gradlew bootJar --no-daemon
```

확인한 GHCR workflow는 `backend`를 build context로 사용합니다. 코어도 backend 아래에 있으므로 context를 변경할 필요가 없습니다. 기존 Dockerfile의 JDK/JRE·사용자·실행 설정을 유지합니다.

## 5. Ubuntu에서 검증·빌드

Green House의 backend 폴더에서 실행합니다.

```bash
cd "$GREEN_HOUSE_REPO/backend"

# 실행 권한이 없는 경우에만 부여
chmod +x gradlew

# 코어 테스트 + backend 검사 + 실행 JAR 생성
./gradlew :slip-image-core:test check bootJar --no-daemon
```

생성된 실행 JAR은 `backend/build/libs/`에 있습니다. 코어와 OpenCV JAR은 실행 JAR의 `BOOT-INF/lib`에 포함됩니다. 개발 환경에서는 기존 Green House 실행 절차를 따르고, Docker 배포를 사용하면 해당 환경에서 이미지를 빌드합니다.

Windows에서 파일을 직접 전달받아 `gradlew` 실행 시 `bash\r` 등의 오류가 발생한다면 스크립트가 CRLF인지 확인하고 LF로 변환합니다. 정상적인 파일에는 변환이 필요 없습니다.

```bash
sed -i 's/\r$//' gradlew
```

현재 `.github/workflows/verify.yml`의 backend 명령은 `./gradlew check`와 `./gradlew bootJar`입니다. 모듈을 include하면 기존 비한정 `check`가 코어 검사도 선택하고, `bootJar`는 project 의존성을 함께 패키징합니다. 코어 테스트 보고서도 보관하려면 기존 artifact path에 다음 경로를 추가합니다.

```text
backend/modules/slip-image-core/build/reports/tests
backend/modules/slip-image-core/build/test-results
```

Docker 이미지를 검증할 때는 실제 배포 대상 Linux OS/CPU에서 OpenCV native 로딩과 대표 사진 처리를 확인합니다. JAR 패키징 성공만으로 native 실행까지 검증된 것은 아닙니다.

## 6. Bean·업무 연결과 문서

**설치가 끝났으면 [Green House 연동 가이드](MIGRATION_GREEN_HOUSE.md)의 0절을 읽고, 4.3 → 4.4 → 4.5 → 5절 순서로 진행하세요.** 추가할 파일의 위치, Bean 코드, 서비스 코드, 기존 업로드에서의 호출과 저장할 결과 종류를 해당 문서에 정리했습니다.

1. `com.greenhouse.backend.sales.document`의 기술 연동 설정에서 `SlipProcessors.bounded(...)`를 `@Bean(destroyMethod="close")`으로 한 번 등록합니다.
2. application은 공개 API를 주입받아 `process` 또는 `processAsync`로 호출합니다. 모든 입력 경로가 같은 싱글톤을 사용해야 worker·대기열 한도를 공유합니다.
3. 기존 보안·공통 오류·파일 저장·트랜잭션 계약을 연결합니다. 데모 Controller를 복사하는 방식으로 연동하지 않습니다.
4. 실제 Ubuntu 환경에서 대표 사진 처리와 애플리케이션 종료 시 실행기 정리를 확인합니다.

모듈 배치는 호스트 `docs/04-architecture.md`, Docker 변경은 `docs/07-deployment.md`에도 기록합니다. HTTP API를 추가할 때는 호스트 API 문서·생성 타입·계약 테스트 절차도 따릅니다.

## 7. 함께 커밋할 변경

Green House에서 다음 변경을 함께 검토·커밋합니다.

- `backend/modules/slip-image-core/src/`
- `backend/modules/slip-image-core/build.gradle.kts`
- `backend/settings.gradle.kts`
- `backend/build.gradle.kts`
- `backend/Dockerfile`
- 실제로 추가한 Bean·업무 연결 코드와 호스트 문서
- 실행 권한을 변경했다면 `backend/gradlew`

```bash
cd "$GREEN_HOUSE_REPO"
git status --short
git diff --check
git diff
```

새 모듈 파일은 `git diff`에 나오지 않는 untracked 상태일 수 있으므로 `git status`와 파일 내용도 확인합니다. 모듈 소스와 빌드 설정을 함께 커밋하면 다른 개발자와 Ubuntu CI는 해당 커밋을 받아 동일한 Wrapper로 빌드할 수 있습니다. `build/` 산출물이나 Gradle 캐시는 커밋하지 않습니다.

## 검증·성능 문서도 함께 관리

설치 스크립트는 코어 소스·테스트·빌드 설정만 복사합니다. 동작 원리·검증·성능 기준인 [PROCESSING.md](PROCESSING.md)도 Green House에 복사하고 문서 목차에 연결합니다. 이 이미지 처리 저장소에서 실행합니다.

```bash
mkdir -p "$GREEN_HOUSE_REPO/docs/image-processing"
# 처음 옮길 때 사용. 기존 문서가 있으면 비교 후 병합하며 덮어쓰지 않음
cp -n "$SLIP_REPO/docs/PROCESSING.md" \
  "$GREEN_HOUSE_REPO/docs/image-processing/PROCESSING.md"
```

이식 후 코어 변경과 Ubuntu 측정 결과는 Green House에서 소스·테스트·이 문서를 함께 갱신합니다. 기존 검증은 Windows 환경의 기록입니다. Ubuntu에서는 코어·호스트 테스트, 실제 업로드·저장 흐름과 Linux OpenCV native 실행을 별도로 확인합니다.

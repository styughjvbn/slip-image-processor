# 메모리·실행 제어 개선 검증

검증일 2026-10-16, Windows x64 / Java 21.0.9 / OpenCV 4.9.0-0. 아래는 현재 PC에서 실행한 관측 기록이며 서버 용량 산정이나 p95 성능 보장은 아닙니다.

## 변경 내용

- `Mat.setTo()`가 반환하는 공유 Mat 세 곳을 소유 scope에 등록해 명시적으로 release합니다.
- `MatScope`로 요청·분석 단계·표·행 연결 성분·종이별 임시 Mat의 수명을 나눕니다. 원본 디코딩 Mat은 축소 직후, 교체된 보정 영상은 교체 직후, PNG용 MatOfByte는 byte[] 복사 직후 해제합니다.
- 종이의 경계 상자는 마스크에서 직접 구해 모든 전경 픽셀의 좌표 Mat을 생성하지 않습니다.
- Lab 전경/배경 판정과 label 기록, 색상 경계 비교를 byte[] 일괄 접근으로 바꿉니다. 작은 표 중심 확인 같은 소수의 점 접근은 유지합니다.
- 출력 모드를 먼저 선택해 미선택 overlay의 복제·그리기·PNG 인코딩을 생략합니다. 보고서와 공통 이미지 결과는 모드와 무관하게 유지합니다.
- core JAR의 `BoundedSlipImageProcessor`가 worker·대기열·지연 입력 로딩·취소·종료를 관리합니다. 기본 worker 2, queue 4, OpenCV native 스레드 1입니다. 호스트는 factory로 생성한 인스턴스를 공유하고 close합니다.
- CLI도 같은 실행기를 사용하며 파일을 제한된 수만 제출하고 완료 순으로 저장합니다. HTTP는 동기 응답을 유지하되 core의 같은 한도를 사용합니다.

## 단일 사진 실행

입력 `20260816_091446.jpg`, 3000×4000 JPEG 약 3.03MiB. 자동 회전, maxDimension=2200, Java `-Xmx1200m`. 독립 JVM을 시작해 한 장을 batch로 처리하고 결과를 저장했습니다. 100ms 간격으로 Windows 프로세스 Working Set, Private Bytes, 누적 CPU 시간을 관측했습니다.

| 항목 | 개선 전 ALL | 개선 후 ALL | 개선 후 PRODUCTION |
|---|---:|---:|---:|
| 배치 내부 시간 | 10.55초 | 9.48초 | 9.03초 |
| 기동~종료 wall time | 11.52초 | 10.54초 | 10.11초 |
| 관측 누적 CPU | 14.27 CPU초 | 11.59 CPU초 | 11.05 CPU초 |
| 최대 관측 Working Set | 547.3MiB | 285.5MiB | 260.6MiB |
| 최대 관측 Private Bytes | 777.6MiB | 516.6MiB | 509.6MiB |
| PNG+보고서 파일 수 | 12 | 12 | 6 |
| 결과 크기 | 약 21.6MiB | 약 21.6MiB | 약 9.12MiB |
| 표 / 종이 / 제거 행 | 2 / 2 / 3 | 2 / 2 / 3 | 2 / 2 / 3 |

개선 후 단일 실행은 worker=1, queue=1, OpenCV 스레드=1을 지정했습니다. 개선 전은 기존 native 스레드 설정을 그대로 사용했습니다. 따라서 이 표는 **전체 개선 설정의 단일 사례 비교**이며, 각각의 수정만 분리한 실험이나 반복 평균이 아닙니다. Working Set과 Private Bytes는 힙·native·라이브러리 등 프로세스 전체를 포함하고 단일 요청의 추가 native 할당량이 아닙니다. Private Bytes는 물리 상주 메모리와도 다릅니다. 실제 순간 최대값이 샘플 사이에 발생했을 수 있습니다.

## 같은 JVM의 반복 실행

`PerformanceProbe`(test 소스의 수동 실행 main)로 동일 사진을 2200px / PRODUCTION / worker=1에서 12회 순차 처리했습니다. 결과를 매번 소비하고 이전 결과 전체를 보관하지 않았습니다. 명시적 System.gc()는 사용하지 않았습니다.

- 12회 모두 표 검출 완료, 전체 약 103.27초, 마지막 round 누적 처리 약 102.92초.
- 최대 관측 Working Set 347.2MiB, Private Bytes 502.9MiB.
- 10–30초 / 45–65초 / 80–103초 구간의 Private Bytes 최대값은 각각 502.8 / 502.4 / 502.9MiB.
- Working Set은 위 구간 최대 264.0 → 331.6 → 347.2MiB로 증가했습니다. 동일한 native 누수로 단정할 수 없으며 힙 페이지 상주와 allocator 동작도 포함합니다.
- round 종료 시 Java 힙 사용은 41.9 → 41.3 → 67.6 → 93.7 → 119.7 → 23.2 → 49.5 → 75.5 → 101.0 → 127.0 → 27.8 → 53.9MiB로 GC에 따라 오르내렸습니다.

이 결과는 짧은 반복 관측에서 프로세스 private 사용량이 매 이미지마다 선형 증가하지 않았다는 근거입니다. **12회/약 103초만으로 모든 입력·수시간 부하의 누수 부재를 증명하지는 않습니다.** Java 4.9 Mat wrapper의 native header 삭제는 finalization 동작도 남아 있으며 명시적 release는 주로 픽셀 데이터 참조를 정리합니다. 호스트에서 실제 업로드·저장 흐름을 포함해 긴 부하를 추가 검증해야 합니다.

## 두 worker 실행

같은 수동 probe에서 worker=2, PRODUCTION, 2200px로 두 장씩 3회, 총 6장을 처리했습니다.

- 전체 약 47.12초, 누적 CPU 약 91.94 CPU초.
- 최대 관측 Working Set 462.0MiB, Private Bytes 650.3MiB.
- 단순 평균 처리량은 약 7.85초/장입니다. 위 순차 12회는 기동 포함 약 8.61초/장입니다.
- 두 장이 함께 실행된 첫 round는 약 17.63초였습니다. 전체 처리량과 개별 요청 지연 시간은 다릅니다.

현재 사진/PC에서는 worker를 2개로 늘려도 처리량이 두 배가 되지 않았습니다. CPU·메모리 대역폭·native 연산 경쟁과 다른 시스템 부하에 따라 달라집니다. 실제 배포 서버에서 worker=1과 2를 비교한 뒤 한도를 정하고, core 수만 보고 병렬도를 늘리지 마세요.

## 자동 검증과 재현

전체 자동 테스트는 `VALIDATION.md`에 정리합니다. 새 테스트는 실제 native 엔진의 반복/동시 성공·실패, 출력 모드별 보고서·공통 PNG 동일성, Mat/ROI/setTo alias의 명시적 해제, 실행기 포화·취소·종료·예외 후 복구와 API 429를 검사합니다. OS RSS를 테스트의 절대 상한으로 고정하지 않습니다.

```powershell
.\mvnw.cmd test package

# 단일 처리에 가까운 파일 실행: 대기·저장 시간까지 포함
java -Xmx1200m -jar slip-image-demo/target/slip-image-processor-1.0.0.jar --batch raw-data output rotation-overrides.example.json --workers=1 --queue-capacity=1 --output-mode=PRODUCTION

# test-classes/classes와 OpenPnP JAR을 classpath에 넣은 뒤 probe 실행
# 인수: 입력 경로 maxDimension outputMode workers rounds
java -Xmx1200m -cp "<test-classes>;<classes>;<opencv.jar>" kr.co.slip.opencv.PerformanceProbe raw-data/20260816_091446.jpg 2200 PRODUCTION 1 12
```

순수 코어 probe는 출력 PNG를 byte[]로 생성하지만 파일로 저장하지 않습니다. 따라서 batch/API ZIP 시간과 직접 동일시하지 않습니다. 성능 로그·결과 사진은 `.tools`와 `output-preview`에 보관하며 Git에 넣지 않습니다. 운영에서 병렬 작업의 결과를 오래 보관하거나 모든 요청 바이트를 먼저 적재하면 실행기 제한과 별도로 Java 힙이 증가할 수 있습니다.

# FileGuard

초보자용 파일 무결성 검사 보안 프로젝트입니다.

특정 폴더 안의 파일 해시를 저장해 두고, 나중에 다시 검사해서 파일이 추가, 변경, 삭제되었는지 확인합니다.

## 실행 방법

컴파일:

```bash
javac -d out src/Main.java
```

감시할 폴더의 기준 상태 저장:

```bash
java -cp out Main init ./important_files
```

변경 사항 검사:

```bash
java -cp out Main check ./important_files
```

기준 상태를 현재 상태로 갱신:

```bash
java -cp out Main update ./important_files
```

반복 감시:

```bash
java -cp out Main watch ./important_files 10
```

## 테스트 예시

```bash
mkdir -p important_files
echo "secret config" > important_files/config.txt

javac -d out src/Main.java
java -cp out Main init ./important_files

echo "changed config" > important_files/config.txt
echo "new file" > important_files/new.txt

java -cp out Main check ./important_files
```

예상 결과:

```text
[2026-08-19 12:00:00] [추가됨] new.txt
[2026-08-19 12:00:00] [변경됨] config.txt

요약
- 추가: 1
- 변경: 1
- 삭제: 0
```

`check` 명령은 변경 사항이 있으면 종료 코드 `1`, 변경 사항이 없으면 `0`을 반환합니다. 그래서 스크립트나 자동화 도구에서 탐지 결과를 쉽게 사용할 수 있습니다.

## 코드가 하는 일

1. `init` 명령은 폴더 안의 모든 일반 파일을 읽고 SHA-256 해시를 계산합니다.
2. 계산한 결과를 감시 폴더 안의 `.fileguard-baseline.txt`에 저장합니다.
3. `check` 명령은 현재 파일 해시와 기준 파일의 해시를 비교합니다.
4. 기준에 없던 파일은 `추가됨`, 해시가 달라진 파일은 `변경됨`, 사라진 파일은 `삭제됨`으로 출력합니다.
5. `update` 명령은 변경된 현재 상태를 새 기준 상태로 다시 저장합니다.
6. `watch` 명령은 지정한 초 간격으로 `check`를 반복 실행합니다.

## 배울 수 있는 보안 개념

- 파일 무결성
- SHA-256 해시
- 기준 상태 baseline
- 변경 탐지
- 보안 로그의 기본 형태

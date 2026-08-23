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
[2026-08-19 12:00:00] [변경됨] config.txt
[2026-08-19 12:00:00] [추가됨] new.txt

요약
- 추가: 1
- 변경: 1
- 삭제: 0
```

## 배울 수 있는 보안 개념

- 파일 무결성
- SHA-256 해시
- 기준 상태 baseline
- 변경 탐지
- 보안 로그의 기본 형태

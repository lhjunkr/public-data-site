# 청년정책 매칭 서비스

[![CI](https://github.com/lhjunkr/public-data-site/actions/workflows/ci.yml/badge.svg)](https://github.com/lhjunkr/public-data-site/actions/workflows/ci.yml)

나이 · 지역 · 소득 · 취업상태를 입력하면 조건에 맞는 청년 지원 정책을 찾아주는 서비스입니다.
데이터는 온통청년(한국고용정보원) 공공 API에서 수집합니다.

**공개 URL** — http://161.33.139.178:8080/

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어 · 프레임워크 | Java 21, Spring Boot 4.1 |
| 데이터 | MySQL 8.4, Flyway, Spring Data JPA |
| 외부 연동 | RestClient (온통청년 Open API) |
| 배포 | Docker (멀티스테이지), Docker Compose, Oracle Cloud VM 2대 |
| CI | GitHub Actions (MySQL 서비스 컨테이너 기반 build + test) |

## 구조

```
인터넷 ──:8080──▶ policy-app (Spring Boot)
                      │
                      └──:3306──▶ policy-db (MySQL 8.4, VCN 내부 전용)
```

앱 서버와 DB 서버를 분리했습니다. 무료 티어 1GB 인스턴스 한 대에 Spring과 MySQL을 함께 올리면
메모리 여유가 0이 되고, 한쪽 부하가 다른 쪽을 죽이기 때문입니다.

## 설계 기록

주요 설계 판단과 그 근거는 [`docs/decisions.md`](docs/decisions.md),
ERD는 [`docs/erd.md`](docs/erd.md),
조인 표 채택 근거는 [`docs/adr/ADR-001.md`](docs/adr/ADR-001.md)에 있습니다.

## 로컬 실행

```bash
cp .env.example .env     # 값 채우기
docker compose up -d     # MySQL 기동
./gradlew bootRun
```

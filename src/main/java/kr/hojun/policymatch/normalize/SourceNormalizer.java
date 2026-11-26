package kr.hojun.policymatch.normalize;

import kr.hojun.policymatch.collect.Source;

/**
 * 출처 하나의 원본(policy_raw)을 검색용 표(policy · 연결 표)로 옮기는 정규화기 (D-029, 12/10).
 *
 * 수집기(SourceCollector)와 같은 방식이다. 스프링이 구현 빈을 모두 PolicyBatch 에 넘겨 주고, PolicyBatch 가 출처로 고른다.
 * 새 출처를 붙일 때는 구현 클래스만 추가한다.
 *
 * 구현이 지킬 규칙
 *  - 자기 출처의 원본만 읽고, 자기 출처의 행만 쓰고 비활성한다. 마지막 성공 수집도 자기 출처의 것만 본다.
 *  - 두 메서드 모두 트랜잭션 하나로 돈다. 실패하면 전체를 되돌린다.
 *  - 한 번에 비활성하려는 건수가 상한을 넘으면 멈춘다(MassDeactivationGuard). 강제 반영은 따로 연다.
 */
public interface SourceNormalizer {

    /** 이 정규화기가 맡은 출처. 출처 하나에 정규화기는 하나다. */
    Source source();

    /** 매일 갱신과 관리 API 가 부르는 기본 정규화. */
    NormalizeResult normalize();

    /** 대량 비활성 상한을 보지 않고 반영한다. 사람이 원본을 확인한 뒤 관리 API(force=true)로만 부른다. */
    NormalizeResult normalizeAllowingMassDeactivation();
}

package kr.hojun.policymatch.collect;

/**
 * 출처 하나의 원본을 끝까지 받아 policy_raw 에 적재하는 수집기 (D-029).
 *
 * 스프링 빈으로 등록된 구현을 PolicyBatch 가 모두 받아 출처별로 고른다.
 * 그래서 새 출처(마이홈 · 잡알리오 · 지방공공기관)를 붙일 때는 이 인터페이스를 구현한 클래스만 추가하면 되고,
 * 배치 · 관리 API 코드는 고치지 않는다.
 *
 * 구현이 지킬 규칙
 *  - 수집은 원본 그대로. 정규화는 따로 한다.
 *  - 실행마다 collect_run 에 출처와 결과를 남긴다(D-017). 끝까지 받고 기대 건수의 95% 이상이어야 SUCCESS 다.
 *  - 실패하면 예외를 던진다. 실패한 수집은 정규화 기준이 되지 않는다.
 */
public interface SourceCollector {

    /** 성공으로 볼 최소 비율. 수집 도중 몇 건이 사라지는 정도(O-4)는 허용한다. */
    double MIN_COMPLETE_RATIO = 0.95;

    /** 이 수집기가 맡은 출처. 출처 하나에 수집기는 하나다. */
    Source source();

    /** 원본을 끝까지 받아 적재하고 요약을 돌려준다. 실패하면 collect_run 에 FAILED 를 남기고 예외를 던진다. */
    CollectResult collectAll();
}

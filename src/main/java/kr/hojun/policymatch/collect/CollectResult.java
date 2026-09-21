package kr.hojun.policymatch.collect;

/**
 * 수집 결과 요약.
 *
 * @param totCount    API 가 알려준 전체 건수
 * @param pagesRead   실제로 읽은 페이지 수
 * @param itemsSeen   응답에서 꺼낸 정책 건수
 * @param rowsInTable 적재 후 policy_raw 의 총 행 수
 */
public record CollectResult(int totCount, int pagesRead, int itemsSeen, long rowsInTable) {}

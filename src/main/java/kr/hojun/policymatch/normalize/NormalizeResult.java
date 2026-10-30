package kr.hojun.policymatch.normalize;

/**
 * 정규화 적재 결과.
 *
 * @param candidates    선정 규칙에 맞은 원본 건수
 * @param policies      policy 표에 적재된 건수 (모두 active = TRUE)
 * @param regionLinks   policy_region 에 연결된 행 수
 * @param jobLinks      policy_job 에 연결된 행 수
 * @param categoryLinks policy_category 에 연결된 행 수 (D-018)
 * @param uncategorized 묶음표에 맞는 칩이 하나도 없는 정책 수. 결과에 나오지 않는다 (D-018)
 * @param skipped       정책번호가 없어 건너뛴 건수
 * @param failed        DB 에 넣다가 실패해 건너뛴 정책 수 (예: 값이 칸보다 김). 원인은 서버 로그 (D-019)
 * @param deactivated   이번 실행에서 규칙에서 빠져 비활성(active = FALSE)으로 바뀐 건수
 */
public record NormalizeResult(int candidates, int policies, int regionLinks, int jobLinks, int categoryLinks,
                              int uncategorized, int skipped, int failed, int deactivated) {}

package kr.hojun.policymatch.normalize;

/**
 * 정규화 적재 결과.
 *
 * @param candidates   조건에 맞은 원본 건수
 * @param policies     policy 표에 적재된 건수
 * @param regionLinks  policy_region 에 연결된 행 수
 * @param jobLinks     policy_job 에 연결된 행 수
 * @param skipped      정책번호가 없어 건너뛴 건수
 */
public record NormalizeResult(int candidates, int policies, int regionLinks, int jobLinks, int skipped) {}

package kr.hojun.policymatch.batch;

import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.normalize.NormalizeResult;

/**
 * 수집 + 정규화를 이어서 돌린 결과 (D-020).
 *
 * @param collect        수집 결과
 * @param normalize      정규화 결과
 * @param elapsedSeconds 두 작업을 합친 소요 시간(초)
 */
public record RefreshResult(CollectResult collect, NormalizeResult normalize, long elapsedSeconds) {}

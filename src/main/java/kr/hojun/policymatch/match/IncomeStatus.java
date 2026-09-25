package kr.hojun.policymatch.match;

/** 소득 조건 3-state (D-002). 판정이 아니라 후보를 좁히는 서비스이므로 '판단불가'도 결과에 남긴다. */
public enum IncomeStatus {
    MET,      // 충족
    NOT_MET,  // 미충족
    UNKNOWN   // 판단불가
}

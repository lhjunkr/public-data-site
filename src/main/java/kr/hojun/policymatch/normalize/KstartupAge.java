package kr.hojun.policymatch.normalize;

/**
 * K-Startup 대상 연령(biz_trgt_age) → 나이 범위 (12/10, D-029 ③).
 *
 * 원본은 세 구간 중 여러 개를 콤마로 이은 글이다: "만 20세 미만" · "만 20세 이상 ~ 만 39세 이하" · "만 40세 이상".
 * 고른 구간을 합친 범위로 옮긴다. 경계가 없으면 null 이다 — 매칭 SQL 은 null 을 "경계 없음"으로 본다(D-015).
 *
 *  - 세 구간 모두 = 나이 무관 → (null, null)              12/5 실측 175건 중 115건
 *  - 20~39 + 40 이상 → (20, null)                              36건
 *  - 20~39만 → (20, 39)                                          18건
 *  - 20 미만 + 20~39 → (null, 39)                                 4건
 *  - 40 이상만 → (40, null) · 20 미만만 → (null, 19)             1건씩
 *  - 20 미만 + 40 이상(가운데가 빔)은 범위 하나로 나타낼 수 없다 → 나이 무관으로 넓힌다(D-001: 빠뜨리는 것이 더 나쁨)
 *  - 모르는 글이 하나라도 섞이면 → 나이 무관 + {@link #recognized()} false (정규화가 로그에 남김)
 *
 * 정확한 나이(예: "만 19세~39세")는 글 칸(aply_trgt_ctnt)에만 있고, 그 칸은 '확인 필요'로 보여 준다.
 */
public record KstartupAge(Integer min, Integer max, boolean recognized) {

    private static final KstartupAge ANY = new KstartupAge(null, null, true);

    public static KstartupAge parse(String bizTrgtAge) {
        if (bizTrgtAge == null || bizTrgtAge.isBlank()) {
            return ANY;
        }
        boolean under20 = false, from20to39 = false, from40 = false;
        for (String token : bizTrgtAge.split(",")) {
            String t = token.replaceAll("\\s", "");
            if (t.isEmpty()) {
                continue;
            }
            if (t.contains("20세미만")) {
                under20 = true;
            } else if (t.contains("20세이상") && t.contains("39세이하")) {
                from20to39 = true;
            } else if (t.contains("40세이상")) {
                from40 = true;
            } else {
                return new KstartupAge(null, null, false);
            }
        }
        if (!under20 && !from20to39 && !from40) {
            return ANY;
        }
        if (under20 && !from20to39 && from40) {
            return ANY;
        }
        Integer min = under20 ? null : (from20to39 ? 20 : 40);
        Integer max = from40 ? null : (from20to39 ? 39 : 19);
        return new KstartupAge(min, max, true);
    }

    /** 나이 경계가 하나라도 있으면 true. policy.age_limit_yn 에 Y/N 으로 남긴다(매칭에는 쓰지 않음, D-015). */
    public boolean limited() {
        return min != null || max != null;
    }
}

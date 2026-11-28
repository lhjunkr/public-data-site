package kr.hojun.policymatch.normalize;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import kr.hojun.policymatch.collect.Source;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 출처별 정규화기가 같이 쓰는 policy · 연결 표 쓰기 (12/10, D-029).
 *
 * 모든 메서드가 출처(source)를 받는다. 다른 출처의 행은 읽지도, 바꾸지도 않는다.
 * 온통청년 정규화기(PolicyNormalizer)는 같은 일을 자기 안에 따로 갖고 있다. 잘 돌고 있는 코드라 이번에 옮기지 않았다.
 */
@Repository
public class PolicyStore {

    private static final String UPSERT_POLICY = """
            INSERT INTO policy (
                source, policy_no, title, category_large, category_medium,
                min_age, max_age, age_limit_yn,
                earn_cnd_se_cd, earn_min_amt, earn_max_amt, earn_etc_cn,
                description, support_content, apply_method, required_documents,
                extra_qualification, etc_matters,
                apply_period_text, apply_start_date, apply_end_date,
                apply_url, ref_url, supervising_org, view_count
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) AS src
            ON DUPLICATE KEY UPDATE
                title = src.title, category_large = src.category_large,
                category_medium = src.category_medium,
                min_age = src.min_age, max_age = src.max_age, age_limit_yn = src.age_limit_yn,
                earn_cnd_se_cd = src.earn_cnd_se_cd, earn_min_amt = src.earn_min_amt,
                earn_max_amt = src.earn_max_amt, earn_etc_cn = src.earn_etc_cn,
                description = src.description, support_content = src.support_content,
                apply_method = src.apply_method, required_documents = src.required_documents,
                extra_qualification = src.extra_qualification, etc_matters = src.etc_matters,
                apply_period_text = src.apply_period_text,
                apply_start_date = src.apply_start_date, apply_end_date = src.apply_end_date,
                apply_url = src.apply_url, ref_url = src.ref_url,
                supervising_org = src.supervising_org, view_count = src.view_count,
                active = TRUE
            """;

    private final JdbcTemplate jdbc;

    public PolicyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 이 출처의 마지막 성공 수집 이후에 받은 원본(D-017). 그보다 이전 것은 API 에서 사라진 것이다.
     * 성공한 수집이 없으면 빈 목록이다.
     */
    public List<String> rawSinceLastSuccess(Source source) {
        return jdbc.queryForList("""
                SELECT raw_json FROM policy_raw
                WHERE source = ?
                  AND fetched_at >= (SELECT MAX(started_at) FROM collect_run
                                     WHERE source = ? AND status = 'SUCCESS')
                ORDER BY policy_no
                """, String.class, source.code(), source.code());
    }

    /**
     * rawSinceLastSuccess 와 같은 원본을 번호와 함께 돌려준다 (12/11).
     * 마이홈은 임대 · 분양 구분이 원본 안이 아니라 번호 접두어(rent- · sale-)에 있어 번호가 필요하다.
     */
    public List<RawRow> rawRowsSinceLastSuccess(Source source) {
        return jdbc.query("""
                SELECT policy_no, raw_json FROM policy_raw
                WHERE source = ?
                  AND fetched_at >= (SELECT MAX(started_at) FROM collect_run
                                     WHERE source = ? AND status = 'SUCCESS')
                ORDER BY policy_no
                """, (rs, i) -> new RawRow(rs.getString("policy_no"), rs.getString("raw_json")),
                source.code(), source.code());
    }

    /** 원본 한 행: 번호와 원본 JSON 글자. */
    public record RawRow(String policyNo, String rawJson) {}

    /** 한 행을 넣거나 덮어쓰고, 그 행의 id 를 돌려준다. 다시 나타난 정책은 active = TRUE 로 돌아온다. */
    public long upsert(Source source, PolicyRow r) {
        jdbc.update(UPSERT_POLICY,
                source.code(), r.policyNo(), r.title(), r.categoryLarge(), r.categoryMedium(),
                r.minAge(), r.maxAge(), r.ageLimitYn(),
                r.earnCndSeCd(), r.earnMinAmt(), r.earnMaxAmt(), r.earnEtcCn(),
                r.description(), r.supportContent(), r.applyMethod(), r.requiredDocuments(),
                r.extraQualification(), r.etcMatters(),
                r.applyPeriodText(), sqlDate(r.applyStartDate()), sqlDate(r.applyEndDate()),
                r.applyUrl(), r.refUrl(), r.supervisingOrg(), r.viewCount());
        // 유니크 키가 (source, policy_no) 라서 source 를 함께 줘야 인덱스로 바로 찾는다
        Long id = jdbc.queryForObject("SELECT id FROM policy WHERE source = ? AND policy_no = ?", Long.class,
                source.code(), r.policyNo());
        return id == null ? -1 : id;
    }

    /**
     * 한 정책의 연결 표(policy_sido / policy_job / policy_category)를 values 와 같게 맞추고 연결 수를 돌려준다.
     * 이미 같으면 아무것도 쓰지 않는다. table · column 은 정규화기 안의 상수만 넘긴다(사용자 입력이 아니다).
     */
    public int syncLinks(String table, String column, long policyId, Set<String> values) {
        List<String> current = jdbc.queryForList(
                "SELECT " + column + " FROM " + table + " WHERE policy_id = ?", String.class, policyId);
        if (current.size() == values.size() && values.containsAll(current)) {
            return values.size();
        }
        jdbc.update("DELETE FROM " + table + " WHERE policy_id = ?", policyId);
        if (!values.isEmpty()) {
            insertRows("INSERT INTO " + table + " (policy_id, " + column + ") VALUES ", values, policyId);
        }
        return values.size();
    }

    /**
     * 이 출처의 활성 · 마감 전 정책이 가진 신청 · 참고 주소 중 host 가 들어간 것. 다른 출처와 같은 공고를 찾는 데 쓴다.
     * 마감이 지난 정책은 화면에 안 나오므로 빼야, 그 정책 때문에 아직 모집 중인 공고를 숨기는 일이 없다.
     */
    public List<String> openPolicyUrlsContaining(Source source, String host) {
        String like = "%" + host + "%";
        return jdbc.queryForList("""
                SELECT url FROM (
                    SELECT apply_url AS url FROM policy
                    WHERE source = ? AND active = TRUE AND (apply_end_date IS NULL OR apply_end_date >= CURDATE())
                      AND apply_url LIKE ?
                    UNION ALL
                    SELECT ref_url FROM policy
                    WHERE source = ? AND active = TRUE AND (apply_end_date IS NULL OR apply_end_date >= CURDATE())
                      AND ref_url LIKE ?
                ) urls
                """, String.class, source.code(), like, source.code(), like);
    }

    /**
     * 이 출처의 활성 · 마감 전 정책의 (기관, 제목). 다른 출처와 같은 공고가 있는지 세는 데만 쓴다(12/14, 합치지는 않는다).
     */
    public List<OrgTitle> openOrgTitles(Source source) {
        return jdbc.query("""
                SELECT supervising_org, title FROM policy
                WHERE source = ? AND active = TRUE AND (apply_end_date IS NULL OR apply_end_date >= CURDATE())
                """, (rs, rowNum) -> new OrgTitle(rs.getString(1), rs.getString(2)), source.code());
    }

    public record OrgTitle(String org, String title) {}

    /** 이 출처의 지금 활성 정책 수. 대량 비활성 상한의 기준이다. */
    public int countActive(Source source) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM policy WHERE source = ? AND active = TRUE",
                Integer.class, source.code());
        return n == null ? 0 : n;
    }

    /**
     * deactivateOthers 가 비활성으로 바꿀 행 중 마감 전인 것(마감일이 없거나 오늘 이후)의 수. 바꾸지 않고 세기만 한다.
     * 대량 비활성 상한(MassDeactivationGuard)이 이 수를 본다. 마감일이 지나 응답에서 빠진 공고는 정상적으로 사라진 것이라
     * 세지 않는다(D-019 "12/16 추가"). 오늘(DB 시간대 +09:00 = 한국 날짜)이 마감인 공고는 아직 신청할 수 있으므로 센다.
     */
    public int countToDeactivate(Source source, List<String> selectedNos) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy WHERE source = ? AND active = TRUE"
                        + " AND (apply_end_date IS NULL OR apply_end_date >= CURDATE()) AND policy_no NOT IN ("
                        + placeholders(selectedNos) + ")",
                Integer.class, sourceAnd(source, selectedNos));
        return n == null ? 0 : n;
    }

    /** 이번에 선정되지 않은 이 출처의 정책을 비활성으로 바꾸고, 바뀐 행 수를 돌려준다. 이미 비활성인 행은 건드리지 않는다. */
    public int deactivateOthers(Source source, List<String> selectedNos) {
        return jdbc.update(
                "UPDATE policy SET active = FALSE WHERE source = ? AND active = TRUE AND policy_no NOT IN ("
                        + placeholders(selectedNos) + ")",
                sourceAnd(source, selectedNos));
    }

    /** 여러 행을 한 문장으로 넣는다: INSERT ... VALUES (?, ?), (?, ?), ... 값은 모두 파라미터로 넘긴다. */
    private void insertRows(String head, Collection<String> values, long policyId) {
        List<Object> args = new ArrayList<>(values.size() * 2);
        for (String value : values) {
            args.add(policyId);
            args.add(value);
        }
        jdbc.update(head + String.join(", ", Collections.nCopies(values.size(), "(?, ?)")), args.toArray());
    }

    private static String placeholders(List<String> values) {
        return String.join(",", Collections.nCopies(values.size(), "?"));
    }

    private static Object[] sourceAnd(Source source, List<String> selectedNos) {
        List<Object> args = new ArrayList<>(selectedNos.size() + 1);
        args.add(source.code());
        args.addAll(selectedNos);
        return args.toArray();
    }

    private static Date sqlDate(LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }
}

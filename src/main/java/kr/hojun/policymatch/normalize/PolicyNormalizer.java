package kr.hojun.policymatch.normalize;

import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * policy_raw 에 쌓인 원본을 정규화 표(policy / policy_region / policy_job)로 옮긴다.
 *
 * 멱등성 설계
 *  - policy 는 policy_no 가 UNIQUE 이므로 ON DUPLICATE KEY UPDATE 로 덮어쓴다.
 *  - 연결 표는 "지우고 다시 넣는다". 정책의 지원 지역이 줄어든 경우까지 반영하려면
 *    추가만 해서는 안 되고, 그 정책의 기존 연결을 비운 뒤 현재 값으로 다시 채워야 한다.
 */
@Service
public class PolicyNormalizer {

    private static final Logger log = LoggerFactory.getLogger(PolicyNormalizer.class);

    /** "20250101 ~ 20251231" 형태의 신청기간에서 날짜 두 개를 꺼낸다. */
    private static final Pattern PERIOD = Pattern.compile("(\\d{8})\\s*~\\s*(\\d{8})");

    private static final String UPSERT_POLICY = """
            INSERT INTO policy (
                policy_no, title, category_large, category_medium,
                min_age, max_age, age_limit_yn,
                earn_cnd_se_cd, earn_min_amt, earn_max_amt, earn_etc_cn,
                description, support_content, apply_method, required_documents,
                extra_qualification, etc_matters,
                apply_period_text, apply_start_date, apply_end_date,
                apply_url, ref_url, supervising_org, view_count
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) AS src
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
                supervising_org = src.supervising_org, view_count = src.view_count
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurationProperties props;

    public PolicyNormalizer(JdbcTemplate jdbc, ObjectMapper objectMapper, CurationProperties props) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Transactional
    public NormalizeResult normalize() {
        List<String> targets = props.getMediumCategories();
        if (targets.isEmpty()) {
            throw new IllegalStateException("curation.medium-categories 가 비어 있습니다.");
        }

        List<String> rawRows = selectCandidates(targets);
        log.info("큐레이션 후보 {}건 (상한 {})", rawRows.size(), props.getLimit());

        int policies = 0, regionLinks = 0, jobLinks = 0, skipped = 0;

        for (String rawJson : rawRows) {
            JsonNode n = objectMapper.readTree(rawJson);
            String policyNo = text(n, "plcyNo");
            if (policyNo == null || policyNo.isBlank()) {
                skipped++;
                continue;
            }

            upsertPolicy(n, policyNo);
            long policyId = policyIdOf(policyNo);
            policies++;

            regionLinks += linkRegions(policyId, text(n, "zipCd"));
            jobLinks += linkJobs(policyId, text(n, "jobCd"));
        }

        log.info("정규화 완료 — policy={} region={} job={} skipped={}",
                policies, regionLinks, jobLinks, skipped);
        return new NormalizeResult(rawRows.size(), policies, regionLinks, jobLinks, skipped);
    }

    /**
     * 중분류에도 콤마 다중값이 있으므로(예: "취업,미래역량강화") 단순 IN 비교로는 놓친다.
     * FIND_IN_SET 은 콤마로 이어진 목록 안에 값이 있는지를 본다.
     */
    private List<String> selectCandidates(List<String> targets) {
        StringBuilder sql = new StringBuilder("""
                SELECT raw_json FROM policy_raw
                WHERE JSON_UNQUOTE(raw_json->'$.plcyAprvSttsCd') = ?
                  AND (""");
        List<Object> args = new ArrayList<>();
        args.add(props.getApprovedStatusCode());

        for (int i = 0; i < targets.size(); i++) {
            sql.append(i == 0 ? "" : " OR ")
               .append("FIND_IN_SET(?, JSON_UNQUOTE(raw_json->'$.mclsfNm')) > 0");
            args.add(targets.get(i));
        }
        sql.append(") ORDER BY policy_no LIMIT ").append(props.getLimit());

        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    private void upsertPolicy(JsonNode n, String policyNo) {
        String periodText = text(n, "aplyYmd");
        Date start = null, end = null;
        if (periodText != null) {
            Matcher m = PERIOD.matcher(periodText);
            if (m.find()) {
                start = toDate(m.group(1));
                end = toDate(m.group(2));
            }
        }

        jdbc.update(UPSERT_POLICY,
                policyNo,
                text(n, "plcyNm"),
                text(n, "lclsfNm"),
                text(n, "mclsfNm"),
                number(n, "sprtTrgtMinAge"),
                number(n, "sprtTrgtMaxAge"),
                text(n, "sprtTrgtAgeLmtYn"),
                text(n, "earnCndSeCd"),
                number(n, "earnMinAmt"),
                number(n, "earnMaxAmt"),
                text(n, "earnEtcCn"),
                text(n, "plcyExplnCn"),
                text(n, "plcySprtCn"),
                text(n, "plcyAplyMthdCn"),
                text(n, "sbmsnDcmntCn"),
                text(n, "addAplyQlfcCndCn"),
                text(n, "etcMttrCn"),
                periodText,
                start,
                end,
                text(n, "aplyUrlAddr"),
                text(n, "refUrlAddr1"),
                text(n, "sprvsnInstCdNm"),
                number(n, "inqCnt"));
    }

    private long policyIdOf(String policyNo) {
        return jdbc.queryForObject("SELECT id FROM policy WHERE policy_no = ?", Long.class, policyNo);
    }

    /**
     * policy_region 은 region 표를 참조한다. 아직 지역 코드 마스터가 없으므로,
     * 처음 만나는 코드는 그 자리에서 만들어 둔다(정식 지역명 채우기는 별도 과제).
     */
    private int linkRegions(long policyId, String zipCd) {
        Set<String> codes = split(zipCd);
        jdbc.update("DELETE FROM policy_region WHERE policy_id = ?", policyId);
        if (codes.isEmpty()) {
            return 0;
        }
        for (String code : codes) {
            String sido = code.length() >= 2 ? code.substring(0, 2) : code;
            jdbc.update("INSERT IGNORE INTO region (code, sido, name) VALUES (?, ?, ?)", code, sido, code);
        }
        List<Object[]> batch = codes.stream().map(c -> new Object[] { policyId, c }).toList();
        jdbc.batchUpdate("INSERT IGNORE INTO policy_region (policy_id, region_code) VALUES (?, ?)", batch);
        return codes.size();
    }

    private int linkJobs(long policyId, String jobCd) {
        Set<String> codes = split(jobCd);
        jdbc.update("DELETE FROM policy_job WHERE policy_id = ?", policyId);
        if (codes.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = codes.stream().map(c -> new Object[] { policyId, c }).toList();
        jdbc.batchUpdate("INSERT IGNORE INTO policy_job (policy_id, job_code) VALUES (?, ?)", batch);
        return codes.size();
    }

    /** 콤마 다중값을 쪼갠다. 같은 값이 두 번 오는 경우가 실제로 있어 집합으로 받는다. */
    private static Set<String> split(String commaSeparated) {
        Set<String> out = new LinkedHashSet<>();
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return out;
        }
        for (String token : commaSeparated.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static Date toDate(String yyyymmdd) {
        try {
            return Date.valueOf(yyyymmdd.substring(0, 4) + "-"
                    + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String raw = v.toString();
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw.isBlank() ? null : raw;
    }

    private static Long number(JsonNode node, String field) {
        String t = text(node, field);
        if (t == null) {
            return null;
        }
        try {
            return Long.parseLong(t.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

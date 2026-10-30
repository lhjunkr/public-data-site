package kr.hojun.policymatch.normalize;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.hojun.policymatch.category.CategoryCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * policy_raw 에 쌓인 원본을 정규화 표(policy / policy_region / policy_job / policy_category)로 옮긴다.
 *
 * 멱등성 설계
 *  - policy 는 policy_no 가 UNIQUE 이므로 ON DUPLICATE KEY UPDATE 로 덮어쓴다.
 *  - 연결 표는 현재 값과 같게 맞춘다. 정책의 지원 지역이 줄어든 경우까지 반영하려면 추가만 해서는 안 되고,
 *    달라졌으면 그 정책의 기존 연결을 비운 뒤 현재 값으로 다시 채운다. 같으면 아무것도 쓰지 않는다.
 *
 * 선정과 비활성 (D-014, D-017, D-018)
 *  - 승인된 정책은 분류와 상관없이 전부 적재하고 active = TRUE 로 둔다. 건수 상한은 없다.
 *    화면에 어느 분류로 보일지는 묶음표(CategoryCatalog)가 정하고, policy_category 에 칩 코드로 남긴다.
 *  - 기준은 마지막으로 "성공한" 수집이다. 실패한 수집은 정상 정책을 비활성하지 못한다.
 *  - 규칙에서 빠진 정책은 지우지 않고 active = FALSE 로 바꾼다. 다시 규칙에 들면 TRUE 로 돌아온다.
 *
 * 쓰기량 (D-018): 대상이 742건에서 3,763건으로 늘어 매일 도는 배치의 쓰기를 줄였다.
 *  - 지역 코드 마스터(region)는 이미 있는 코드를 처음에 한 번 읽어 두고 새 코드만 넣는다.
 *  - 연결 표는 여러 행을 한 문장(INSERT ... VALUES (..), (..))으로 넣고, 값이 같으면 건너뛴다.
 */
@Service
public class PolicyNormalizer {

    private static final Logger log = LoggerFactory.getLogger(PolicyNormalizer.class);

    /** "20250101 ~ 20251231" 형태의 신청기간에서 날짜 두 개를 꺼낸다. */
    private static final Pattern PERIOD = Pattern.compile("(\\d{8})\\s*~\\s*(\\d{8})");

    /**
     * D-016: 이 값 이상인 소득 금액은 원 단위로 보고 만원으로 바꾼다.
     * 만원 단위로 10만(= 10억 원)이 넘는 청년 소득 기준은 없고, 원 단위로 10만 원 이하인 연소득 기준도 없다.
     */
    static final long WON_THRESHOLD = 100_000L;

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
                supervising_org = src.supervising_org, view_count = src.view_count,
                active = TRUE
            """;

    /**
     * 이번에 선정되지 않은 정책을 비활성으로 바꾼다. {@code ?} 자리는 실행할 때 선정된 정책 수만큼 채운다.
     * 이미 비활성인 행은 건드리지 않는다(active = TRUE 조건). 바뀐 것이 없는 행의 updated_at 을 지키기 위해서다.
     */
    private static final String DEACTIVATE_OTHERS =
            "UPDATE policy SET active = FALSE WHERE active = TRUE AND policy_no NOT IN (%s)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurationProperties props;
    private final CategoryCatalog categories;

    public PolicyNormalizer(JdbcTemplate jdbc, ObjectMapper objectMapper, CurationProperties props,
                            CategoryCatalog categories) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.props = props;
        this.categories = categories;
    }

    @Transactional
    public NormalizeResult normalize() {
        long startedMs = System.currentTimeMillis();
        List<String> rawRows = selectCandidates();
        log.info("선정 후보 {}건", rawRows.size());

        Set<String> knownRegions = new HashSet<>(jdbc.queryForList("SELECT code FROM region", String.class));
        Set<String> unmappedMediums = new TreeSet<>();
        int policies = 0, regionLinks = 0, jobLinks = 0, categoryLinks = 0, uncategorized = 0, skipped = 0;
        List<String> selectedNos = new ArrayList<>();

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
            selectedNos.add(policyNo);

            Set<String> regionCodes = split(text(n, "zipCd"));
            addNewRegions(regionCodes, knownRegions);
            regionLinks += syncLinks("policy_region", "region_code", policyId, regionCodes);
            jobLinks += syncLinks("policy_job", "job_code", policyId, split(text(n, "jobCd")));

            String mediums = text(n, "mclsfNm");
            Set<String> chips = categories.chipsOf(mediums);
            unmappedMediums.addAll(categories.unmappedOf(mediums));
            categoryLinks += syncLinks("policy_category", "category_code", policyId, chips);
            if (chips.isEmpty()) {
                uncategorized++;
            }
        }

        // 선정이 0건인데 그대로 진행하면 모든 정책이 비활성이 된다. 성공한 수집이 없거나 데이터가 깨진 상황으로 보고 멈춘다.
        if (selectedNos.isEmpty()) {
            throw new IllegalStateException("선정된 정책이 0건이라 비활성 처리를 하지 않고 중단합니다. "
                    + "성공한 수집(collect_run 의 SUCCESS)이 있는지, policy_raw 가 비어 있지 않은지 확인하십시오.");
        }
        int deactivated = deactivateOthers(selectedNos);

        if (!unmappedMediums.isEmpty()) {
            // 새 분류가 생겼다는 신호다. 묶음표(application.yml 의 category.tabs)에 넣어야 화면에 나온다.
            log.warn("묶음표에 없는 중분류 {}개: {}", unmappedMediums.size(), unmappedMediums);
        }
        log.info("정규화 완료 — policy={} region={} job={} category={} uncategorized={} skipped={} deactivated={} ({}ms)",
                policies, regionLinks, jobLinks, categoryLinks, uncategorized, skipped, deactivated,
                System.currentTimeMillis() - startedMs);
        return new NormalizeResult(rawRows.size(), policies, regionLinks, jobLinks, categoryLinks,
                uncategorized, skipped, deactivated);
    }

    /**
     * 선정 규칙: 승인됨(D-007) + 마지막으로 성공한 수집 이후 확인됨(D-017). 분류와 건수는 따지지 않는다(D-018).
     *
     *  - 한 번 수집할 때 모든 행에 같은 시각(수집 시작 시각)이 들어간다. 그래서 fetched_at 이
     *    마지막 성공 수집의 시작 시각보다 이전인 행은 그 뒤로 한 번도 보이지 않은 정책, 즉 API 에서 사라진 정책이다.
     *  - "같다(=)"가 아니라 "이후(>=)"로 비교한다. 성공 수집 뒤에 실패한 수집이 있어도, 거기서 갱신된 정책은
     *    존재가 확인된 것이고, 갱신되지 못한 정책도 성공 수집 때의 값으로 남아 함께 선정된다.
     *  - 성공한 수집이 없으면 MAX 가 NULL 이라 아무것도 선정되지 않고, normalize() 가 0건으로 멈춘다.
     *  - 마감 여부는 여기서 보지 않는다. 매칭할 때 거른다(D-008).
     */
    private List<String> selectCandidates() {
        return jdbc.queryForList("""
                SELECT raw_json FROM policy_raw
                WHERE JSON_UNQUOTE(raw_json->'$.plcyAprvSttsCd') = ?
                  AND fetched_at >= (SELECT MAX(started_at) FROM collect_run WHERE status = 'SUCCESS')
                ORDER BY policy_no
                """, String.class, props.getApprovedStatusCode());
    }

    /** 이번에 선정된 정책 번호 목록에 없는 정책을 비활성으로 바꾸고, 바뀐 행 수를 돌려준다. */
    private int deactivateOthers(List<String> selectedNos) {
        String placeholders = String.join(",", Collections.nCopies(selectedNos.size(), "?"));
        return jdbc.update(DEACTIVATE_OTHERS.formatted(placeholders), selectedNos.toArray());
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
                toManwon(number(n, "earnMinAmt")),
                toManwon(number(n, "earnMaxAmt")),
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
     * 이미 있는 코드는 normalize() 시작 때 읽어 둔 knownRegions 로 걸러 다시 넣지 않는다.
     */
    private void addNewRegions(Set<String> codes, Set<String> knownRegions) {
        List<String> newCodes = codes.stream().filter(c -> !knownRegions.contains(c)).toList();
        if (newCodes.isEmpty()) {
            return;
        }
        insertRows("INSERT IGNORE INTO region (code, sido, name) VALUES ", "(?, ?, ?)", newCodes,
                code -> new Object[] { code, code.length() >= 2 ? code.substring(0, 2) : code, code });
        knownRegions.addAll(newCodes);
    }

    /**
     * 한 정책의 연결 표(policy_region / policy_job / policy_category)를 values 와 같게 맞추고 연결 수를 돌려준다.
     * 이미 같으면 아무것도 쓰지 않는다. table · column 은 이 클래스 안의 상수만 넘긴다(사용자 입력이 아니다).
     */
    private int syncLinks(String table, String column, long policyId, Set<String> values) {
        List<String> current = jdbc.queryForList(
                "SELECT " + column + " FROM " + table + " WHERE policy_id = ?", String.class, policyId);
        if (current.size() == values.size() && values.containsAll(current)) {
            return values.size();
        }
        jdbc.update("DELETE FROM " + table + " WHERE policy_id = ?", policyId);
        if (!values.isEmpty()) {
            insertRows("INSERT INTO " + table + " (policy_id, " + column + ") VALUES ", "(?, ?)", values,
                    value -> new Object[] { policyId, value });
        }
        return values.size();
    }

    /** 여러 행을 한 문장으로 넣는다: INSERT ... VALUES (?, ?), (?, ?), ... 값은 모두 파라미터로 넘긴다. */
    private <T> void insertRows(String head, String rowPlaceholder, Collection<T> items, Function<T, Object[]> toRow) {
        List<Object> args = new ArrayList<>();
        for (T item : items) {
            Collections.addAll(args, toRow.apply(item));
        }
        String sql = head + String.join(", ", Collections.nCopies(items.size(), rowPlaceholder));
        jdbc.update(sql, args.toArray());
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

    /**
     * D-016: 소득 금액을 만원 단위로 통일한다. 원 단위로 들어온 값(WON_THRESHOLD 이상)만 바꾼다.
     * 나눌 때는 올린다 — 상한 기준이므로 경계에서 결과를 좁히지 않기 위해서다. 예: 43,056,240원 → 4,306만원.
     */
    static Long toManwon(Long amount) {
        if (amount == null || amount < WON_THRESHOLD) {
            return amount;
        }
        return (amount + 9_999L) / 10_000L;
    }
}

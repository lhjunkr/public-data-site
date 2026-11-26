package kr.hojun.policymatch.normalize;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.hojun.policymatch.category.CategoryCatalog;
import kr.hojun.policymatch.collect.Source;
import kr.hojun.policymatch.job.JobStatus;
import kr.hojun.policymatch.region.SidoNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * K-Startup 지원사업 공고 원본(policy_raw, source = kstartup)을 검색용 표로 옮긴다 (12/10, D-029).
 *
 * 칸 옮기기 (docs/erd.md "소스별로 칸에 넣는 값")
 *  - 제목 biz_pbanc_nm · 기관 pbanc_ntrp_nm · 접수 시작/마감 pbanc_rcpt_bgng_dt/end_dt · 신청 주소 detl_pg_url
 *  - 나이: 세 구간을 합친 범위(KstartupAge). 정확한 나이는 글(aply_trgt_ctnt)에만 있어 '확인 필요'로 둔다(D-003)
 *  - 지역: supt_regin → 시도 코드(SidoNames). "전국"은 모든 시도로 펼친다. 시군구 표(policy_region)는 쓰지 않는다(D-027)
 *  - 분류: 칩은 일자리 · 창업(jobs.startup) 하나. 원본 분야(supt_biz_clsfc)는 category_medium 과 지원 내용에 남긴다
 *  - 취업 상태: 제한없음(0013010) 한 줄 → 지금 매칭 SQL 이 그대로 통과시킨다
 *  - 소득: 비움 → 매칭은 '판단불가'를 내고, 화면이 출처를 보고 소득 표시를 숨긴다
 *  - 글: HTML 엔티티를 풀고(TextCleaner) 지원 내용에 화면용으로 정리한다. 담당자 이메일 · 전화 · 팩스 칸은 싣지 않는다
 *
 * 다른 출처와 같은 공고 (D-029 ⑤ "같은 식별자가 있을 때만")
 *  - 마감 전 활성 온통청년 정책의 신청 · 참고 주소가 K-Startup 공고 번호(pbancSn)를 가리키면 같은 공고다.
 *    이 공고는 싣지 않고 duplicates 로 센다. 온통청년 쪽을 남기는 이유: 나이 · 소득 · 취업 상태 조건이 더 자세하다.
 *  - 제목이 비슷하다고 합치지 않는다. 잘못 합치면 공고가 사라진다(D-001).
 *
 * 선정 · 비활성 · 실패 격리 · 대량 변경 감지는 온통청년 정규화기와 같은 규칙을 이 출처 안에서만 쓴다(D-014 · D-017 · D-019).
 *  - 승인 상태 칸이 없다. 수집할 때 이미 모집 중(rcrt_prgs_yn = Y)만 받았다.
 *  - 마감은 여기서 거르지 않고 매칭할 때 거른다(D-008).
 */
@Service
public class KstartupNormalizer implements SourceNormalizer {

    private static final Logger log = LoggerFactory.getLogger(KstartupNormalizer.class);
    private static final Source SOURCE = Source.KSTARTUP;

    /** 화면 칩: 일자리 탭 · 창업 칩 (application.yml 의 category.tabs) */
    static final String CHIP = "jobs.startup";
    static final String CATEGORY_LARGE = "창업";

    /** 온통청년 정책 주소에서 K-Startup 공고 번호를 꺼낸다. 예: …/bizpbanc-ongoing.do?schM=view&pbancSn=179871 */
    static final String KSTARTUP_HOST = "k-startup.go.kr";
    private static final Pattern PBANC_SN = Pattern.compile("[?&]pbancSn=(\\d+)");

    /** 이 날 이후의 마감일은 "상시"로 본다(D-029: 9999-09-09 같은 값). */
    static final LocalDate OPEN_ENDED_FROM = LocalDate.of(2099, 1, 1);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** D-019: 정책 단위 실패를 이만큼(또는 후보의 1%)까지는 건너뛰고, 넘으면 전체를 되돌린다. */
    static final int MIN_FAILURE_LIMIT = 10;

    private final PolicyStore store;
    private final SidoNames sidoNames;
    private final ObjectMapper objectMapper;

    /**
     * 칩 코드를 여기서 직접 정하므로, 묶음표(category.tabs)에서 그 칩이 사라지거나 닫히면 공고가 조용히 검색에서 빠진다.
     * 그래서 앱이 뜰 때 확인하고, 어긋나면 시작을 실패시킨다(CategoryCatalog 와 같은 방식).
     */
    public KstartupNormalizer(PolicyStore store, SidoNames sidoNames, ObjectMapper objectMapper,
                              CategoryCatalog categories) {
        if (!categories.openChipCodes().contains(CHIP)) {
            throw new IllegalStateException("K-Startup 칩 " + CHIP + " 이(가) 묶음표의 열린 칩에 없습니다. "
                    + "application.yml 의 category.tabs 를 확인하십시오.");
        }
        this.store = store;
        this.sidoNames = sidoNames;
        this.objectMapper = objectMapper;
    }

    @Override
    public Source source() {
        return SOURCE;
    }

    @Override
    @Transactional
    public NormalizeResult normalize() {
        return run(false);
    }

    /** 같은 객체 안의 호출은 스프링 프록시를 거치지 않으므로, 바깥에서 부르는 두 메서드에 모두 트랜잭션을 붙인다. */
    @Override
    @Transactional
    public NormalizeResult normalizeAllowingMassDeactivation() {
        return run(true);
    }

    private NormalizeResult run(boolean allowMassDeactivation) {
        long startedMs = System.currentTimeMillis();
        // 상한의 기준은 "이번 정규화 전" 활성 건수다. upsert 가 다시 나타난 공고를 활성으로 바꾸므로 먼저 센다
        int activeBefore = store.countActive(SOURCE);
        List<String> rawRows = store.rawSinceLastSuccess(SOURCE);
        Set<String> sameAsYouth = announcementsLinkedFromYouth();
        log.info("K-Startup 정규화 후보 {}건 · 온통청년이 가리키는 공고 번호 {}개", rawRows.size(), sameAsYouth.size());

        int policies = 0, sidoLinks = 0, jobLinks = 0, categoryLinks = 0, noRegion = 0, skipped = 0, duplicates = 0;
        Set<String> unknownRegions = new TreeSet<>();
        Set<String> unknownAges = new TreeSet<>();
        List<String> selectedNos = new ArrayList<>();
        List<String> failedNos = new ArrayList<>();

        for (String rawJson : rawRows) {
            JsonNode n = objectMapper.readTree(rawJson);
            String policyNo = text(n, "pbanc_sn");
            if (policyNo == null || policyNo.isBlank()) {
                skipped++;
                continue;
            }
            if (sameAsYouth.contains(policyNo)) {
                duplicates++;
                log.info("K-Startup 공고 {} 는 온통청년 정책과 같은 공고라 싣지 않음", policyNo);
                continue;
            }

            String region = TextCleaner.clean(text(n, "supt_regin"));
            Set<String> sidos = sidoNames.codesOf(region);
            if (sidos.isEmpty()) {
                // 모르는 지역 이름이면 어느 시도 검색에도 나오지 않는다. 설정(sido.regions)에 넣어야 한다
                noRegion++;
                unknownRegions.add(String.valueOf(region));
            }
            KstartupAge age = KstartupAge.parse(text(n, "biz_trgt_age"));
            if (!age.recognized()) {
                unknownAges.add(text(n, "biz_trgt_age"));
            }

            int[] links;
            try {
                long policyId = store.upsert(SOURCE, toRow(n, policyNo, age));
                links = new int[] {
                        store.syncLinks("policy_sido", "sido", policyId, sidos),
                        store.syncLinks("policy_job", "job_code", policyId, Set.of(JobStatus.UNLIMITED_CODE)),
                        store.syncLinks("policy_category", "category_code", policyId, Set.of(CHIP)) };
            } catch (DataAccessException e) {
                // D-019: 한 공고가 실패해도 나머지는 계속한다. 실패한 공고는 선정 목록에 없으므로 비활성이 된다
                failedNos.add(policyNo);
                log.warn("K-Startup 공고 {} 정규화 실패 — 건너뜀: {}", policyNo, e.getMostSpecificCause().getMessage());
                continue;
            }
            policies++;
            selectedNos.add(policyNo);
            sidoLinks += links[0];
            jobLinks += links[1];
            categoryLinks += links[2];
        }

        int maxFailures = Math.max(MIN_FAILURE_LIMIT, rawRows.size() / 100);
        if (failedNos.size() > maxFailures) {
            throw new IllegalStateException("K-Startup 정규화 실패가 " + failedNos.size() + "건으로 허용치(" + maxFailures
                    + "건)를 넘어 전체를 되돌립니다. 처음 실패한 공고: " + failedNos.get(0));
        }
        if (selectedNos.isEmpty()) {
            throw new IllegalStateException("K-Startup 선정 공고가 0건이라 비활성 처리를 하지 않고 중단합니다. "
                    + "성공한 수집(collect_run 의 kstartup SUCCESS)이 있는지, policy_raw 가 비어 있지 않은지 확인하십시오.");
        }

        // D-019 "12/8 추가": 바꾸기 전에 몇 건이 꺼질지 세고, 상한을 넘으면 바꾸지 않고 멈춘다
        int toDeactivate = store.countToDeactivate(SOURCE, selectedNos);
        if (allowMassDeactivation) {
            if (toDeactivate > MassDeactivationGuard.limitFor(activeBefore)) {
                log.warn("K-Startup 대량 비활성 강제 반영 — 활성 {}건 중 {}건 비활성 (상한 {}건)",
                        activeBefore, toDeactivate, MassDeactivationGuard.limitFor(activeBefore));
            }
        } else {
            MassDeactivationGuard.check(SOURCE, activeBefore, toDeactivate);
        }
        int deactivated = store.deactivateOthers(SOURCE, selectedNos);

        if (!unknownRegions.isEmpty()) {
            log.warn("K-Startup 시도 매핑에 없는 지역 이름 {}개({}건): {}", unknownRegions.size(), noRegion, unknownRegions);
        }
        if (!unknownAges.isEmpty()) {
            log.warn("K-Startup 대상 연령을 읽지 못해 나이 무관으로 둔 값: {}", unknownAges);
        }
        log.info("K-Startup 정규화 완료 — policy={} sido={} job={} category={} 지역없음={} skipped={} failed={} "
                        + "duplicates={} deactivated={} ({}ms)",
                policies, sidoLinks, jobLinks, categoryLinks, noRegion, skipped, failedNos.size(), duplicates,
                deactivated, System.currentTimeMillis() - startedMs);
        // uncategorized: K-Startup 은 칩을 늘 붙이므로 0. 대신 지역을 못 찾은 수를 로그로 남긴다
        return new NormalizeResult(rawRows.size(), policies, sidoLinks, jobLinks, categoryLinks,
                0, skipped, failedNos.size(), deactivated, duplicates);
    }

    /** 원본 한 건을 policy 행 값으로 바꾼다. */
    PolicyRow toRow(JsonNode n, String policyNo, KstartupAge age) {
        String startText = text(n, "pbanc_rcpt_bgng_dt");
        String endText = text(n, "pbanc_rcpt_end_dt");
        LocalDate start = date(startText);
        LocalDate end = date(endText);
        String period = null;
        if (start != null && end == null && endText != null && date(endText, true) != null) {
            period = startText + " ~ 상시";
        } else if (start != null && end != null) {
            period = startText + " ~ " + endText;
        } else if (end != null) {
            period = "~ " + endText;
        }

        String detailUrl = httpUrl(text(n, "detl_pg_url"));
        String guideUrl = httpUrl(text(n, "biz_gdnc_url"));
        String onlineUrl = httpUrl(text(n, "aply_mthd_onli_rcpt_istc"));

        return new PolicyRow(
                policyNo,
                TextCleaner.clean(text(n, "biz_pbanc_nm")),
                CATEGORY_LARGE,
                TextCleaner.clean(text(n, "supt_biz_clsfc")),
                age.min(),
                age.max(),
                age.limited() ? "Y" : "N",
                null, null, null, null,
                TextCleaner.clean(text(n, "pbanc_ctnt")),
                supportContent(n),
                TextCleaner.clean(text(n, "eval_method")),
                null,
                TextCleaner.clean(text(n, "aply_trgt_ctnt")),
                TextCleaner.clean(text(n, "prfn_matr")),
                period,
                start,
                end,
                detailUrl,
                guideUrl != null ? guideUrl : onlineUrl,
                TextCleaner.clean(text(n, "pbanc_ntrp_nm")),
                null);
    }

    /**
     * 상세 조회(지원 내용 보기)에 보일 글. 표 칸에 없는 공고 정보를 사람이 읽을 순서로 모은다(D-029 ②).
     * 담당자 이메일(aply_mthd_eml_rcpt_istc) · 팩스 · 방문 주소 · 전화(prch_cnpl_no)는 넣지 않는다 — 원본 사이트에서 확인한다.
     */
    String supportContent(JsonNode n) {
        StringBuilder out = new StringBuilder();
        line(out, "지원 분야", text(n, "supt_biz_clsfc"));
        line(out, "지원 지역", text(n, "supt_regin"));
        line(out, "신청 대상", spaced(text(n, "aply_trgt")));
        line(out, "대상 연령", spaced(text(n, "biz_trgt_age")));
        line(out, "창업 업력", spaced(text(n, "biz_enyy")));
        String org = TextCleaner.clean(text(n, "pbanc_ntrp_nm"));
        String orgType = TextCleaner.clean(text(n, "sprv_inst"));
        line(out, "주관 기관", org == null ? null : (orgType == null ? org : org + " (" + orgType + ")"));
        section(out, "대상 상세", text(n, "aply_trgt_ctnt"));
        section(out, "제외 대상", text(n, "aply_excl_trgt_ctnt"));
        section(out, "공고 내용", text(n, "pbanc_ctnt"));
        section(out, "지원 내용", text(n, "support_ctnt"));
        section(out, "신청 방법", text(n, "eval_method"));
        section(out, "우대 사항", text(n, "prfn_matr"));
        String body = out.toString().strip();
        return body.isEmpty() ? null : body;
    }

    /**
     * 마감 전 활성 온통청년 정책이 주소로 가리키는 K-Startup 공고 번호들.
     * 주소에 pbancSn 이 있을 때만 센다. K-Startup 첫 화면이나 목록 주소는 특정 공고가 아니므로 같은 공고로 보지 않는다.
     */
    Set<String> announcementsLinkedFromYouth() {
        Set<String> numbers = new HashSet<>();
        for (String url : store.openPolicyUrlsContaining(Source.YOUTH, KSTARTUP_HOST)) {
            numbers.addAll(pbancSnOf(url));
        }
        return numbers;
    }

    static Set<String> pbancSnOf(String url) {
        Set<String> out = new HashSet<>();
        if (url == null || !url.contains(KSTARTUP_HOST)) {
            return out;
        }
        // 온통청년 주소에 &amp; 가 그대로 든 것이 있다(11/26 실측: …PBC010&amp;schM=view&amp;pbancSn=176019). 풀고 찾는다
        Matcher m = PBANC_SN.matcher(TextCleaner.decodeEntities(url));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static void line(StringBuilder out, String label, String value) {
        String v = TextCleaner.clean(value);
        if (v != null) {
            out.append(label).append(": ").append(v).append('\n');
        }
    }

    private static void section(StringBuilder out, String label, String value) {
        String v = TextCleaner.clean(value);
        if (v != null) {
            out.append('\n').append('[').append(label).append("]\n").append(v).append('\n');
        }
    }

    /** "대학생,일반인" → "대학생, 일반인" */
    private static String spaced(String csv) {
        return csv == null ? null : csv.replaceAll("\\s*,\\s*", ", ");
    }

    /** http(s) 주소만 남긴다. 화면도 http(s) 가 아닌 주소는 링크로 만들지 않는다. */
    private static String httpUrl(String url) {
        String u = url == null ? null : url.strip();
        return u != null && u.matches("(?i)^https?://.+") ? u : null;
    }

    /** YYYYMMDD → 날짜. 2099년 이후(상시)나 읽을 수 없는 값은 null. */
    static LocalDate date(String yyyymmdd) {
        return date(yyyymmdd, false);
    }

    private static LocalDate date(String yyyymmdd, boolean keepOpenEnded) {
        if (yyyymmdd == null || !yyyymmdd.strip().matches("\\d{8}")) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(yyyymmdd.strip(), YYYYMMDD);
            return !keepOpenEnded && !d.isBefore(OPEN_ENDED_FROM) ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 칸의 글을 JSON 이스케이프를 푼 그대로 꺼낸다(\n → 줄바꿈). 숫자 칸(pbanc_sn)은 숫자 그대로의 글자.
     * Jackson 2 → 3 에서 값 추출 메서드 이름이 바뀌어, 버전과 상관없는 toString() + readValue 로 꺼낸다(JsonNodes 와 같은 이유).
     */
    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String raw = v.toString();
        String value = raw.startsWith("\"") ? objectMapper.readValue(raw, String.class) : raw;
        return value.isBlank() ? null : value;
    }
}

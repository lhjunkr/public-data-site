package kr.hojun.policymatch.normalize;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * 지방공공기관 채용공고 원본(policy_raw, source = cleaneye)을 검색용 표로 옮긴다 (12/14, D-029).
 *
 * 칸 옮기기 (docs/erd.md "소스별로 칸에 넣는 값")
 *  - 한 행 = 공고의 직급 · 직무 하나(NO). 제목 ENT_TITLE · 기관 ENT_NAME · 접수 PUB_DATE ~ PUB_END_DATE (YYYY-MM-DD)
 *  - 고용형태 JOB_TYPE(값 1개) → 공공기관 채용 탭의 칩. 이름 → 칩은 묶음표(application.yml)에 잡알리오 이름과 함께 둔다
 *    (일반정규직 → 정규직 칩, 기간제 · 일반계약직 · 전문계약직 → 비정규직 칩, 인턴 → 청년인턴 칩, 무기계약직 → 무기계약직 칩)
 *  - 채용 구분 EMPLOY_GB → '신입'이 있으면 신입 가능 칩. 시도 SIDO_CD(시도 정식 이름) → 시도 코드(policy_sido)
 *  - 나이 · 소득: 칸이 없어 비운다(D-029 ③). 정년(RETIREMENT)은 나이 조건이 아니라 쓰지 않는다. 취업 상태: 제한없음 한 줄
 *  - 확인 필요(extra_qualification): 지역 제한(LOCAL_YN = Y, 거주지 · 지역인재 조건)과 자격증 칸(ENT_LICENSE1 ~ 4)
 *  - 접수 방법(ACCUSATION) · 문의처(REFERENCE) · 기타(JOB_SEEK_ETC)는 싣지 않는다. 12/5 측정에서 담당 메일 36행 ·
 *    전화 316행이 이 칸들에 있었다. 접수 · 문의는 클린아이 원문(URL)에서 본다(잡알리오 · K-Startup · 마이홈과 같은 기준)
 *  - 'null' · '-' 글자는 빈 값으로 본다(12/5: WORK_PLACE · OFFICE_HOURS 442행 모두 'null' 글자)
 *  - 비상임(임원 공모, 12/5: 17행)은 싣지 않는다. 비상임이사 · 감사 공모라 청년 구직자가 찾는 일자리가 아니다. skipped 로 센다
 *
 * 같은 공고의 여러 행: 같은 (기관, 제목)이 직급 · 직무별로 여러 행이다(12/5: 5개 공고 27행, 최대 9행).
 * 행마다 번호가 다르므로 합치지 않고, 카드가 구분되도록 제목 뒤에 그 공고 안에서 달라지는 값(직급 · 직무 분야 · 고용형태 ·
 * 채용 구분 중 앞의 두 개)을 붙인다. 그래도 같으면 번호 순으로 (1) · (2)를 붙인다(마이홈 단지명과 같은 방식).
 *
 * 잡알리오와 같은 공고: 합치지 않는다(D-029 ⑤ — 같은 식별자가 없다). 12/5 측정에서 기관 이름 겹침 0 · 제목 겹침 0 이었다.
 * 대신 매 정규화마다 마감 전 잡알리오 공고와 기관 이름 · (기관, 제목)이 같은 행을 세어 로그에 남긴다. 0 이 아니면 경고한다.
 *
 * 선정 · 비활성 · 실패 격리 · 대량 변경 감지는 잡알리오와 같은 규칙을 이 출처 안에서만 쓴다(D-014 · D-017 · D-019).
 */
@Service
public class CleaneyeNormalizer implements SourceNormalizer {

    private static final Logger log = LoggerFactory.getLogger(CleaneyeNormalizer.class);
    private static final Source SOURCE = Source.CLEANEYE;

    /** 12/5 측정에서 나온 고용형태 중 싣는 6종. 묶음표에서 하나라도 칩을 잃으면 앱이 뜨지 않게 한다. */
    static final List<String> KNOWN_HIRE_TYPES = List.of("일반정규직", "무기계약직", "기간제", "일반계약직", "전문계약직", "인턴");

    /** 싣지 않는 고용형태. 비상임이사 · 감사 공모(12/5: 17행). */
    static final Set<String> EXCLUDED_HIRE_TYPES = Set.of("비상임");

    /** 이 날 이후의 마감일은 "상시"로 본다. 12/5: 2050-09-26 · 2054-07-10 · 2400-03-19 · 9999-09-09 (잡알리오와 같은 기준) */
    static final LocalDate OPEN_ENDED_FROM = LocalDate.of(2050, 1, 1);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 제목 칸 길이(VARCHAR 255). 직급 등을 붙여 넘으면 자른다. */
    static final int TITLE_MAX = 255;

    /** D-019: 정책 단위 실패를 이만큼(또는 후보의 1%)까지는 건너뛰고, 넘으면 전체를 되돌린다. */
    static final int MIN_FAILURE_LIMIT = 10;

    private final PolicyStore store;
    private final SidoNames sidoNames;
    private final ObjectMapper objectMapper;
    private final CategoryCatalog categories;

    public CleaneyeNormalizer(PolicyStore store, SidoNames sidoNames, ObjectMapper objectMapper,
                              CategoryCatalog categories) {
        RecruitChips.requireChips(categories, KNOWN_HIRE_TYPES, "지방공공기관");
        this.store = store;
        this.sidoNames = sidoNames;
        this.objectMapper = objectMapper;
        this.categories = categories;
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
        log.info("지방공공기관 정규화 후보 {}행", rawRows.size());

        // 1) 원본을 읽고, 번호 없는 행 · 비상임을 뺀다
        int skipped = 0, excluded = 0;
        List<Unit> units = new ArrayList<>();
        for (String rawJson : rawRows) {
            JsonNode n = objectMapper.readTree(rawJson);
            String no = value(n, "NO");
            if (no == null) {
                skipped++;
                continue;
            }
            if (EXCLUDED_HIRE_TYPES.contains(value(n, "JOB_TYPE"))) {
                excluded++;
                continue;
            }
            units.add(new Unit(no, n, value(n, "ENT_NAME"), value(n, "ENT_TITLE"), value(n, "POSITION"),
                    value(n, "ENT_RECRUIT"), value(n, "JOB_TYPE"), value(n, "EMPLOY_GB")));
        }

        // 2) 같은 공고의 여러 행은 제목으로 구분되게 한다
        Map<String, String> titles = displayTitles(units);

        // 3) 잡알리오와 같은 공고가 있는지 센다(합치지 않음)
        Overlap overlap = overlapWithAlio(units);

        int policies = 0, sidoLinks = 0, jobLinks = 0, categoryLinks = 0, noRegion = 0, uncategorized = 0;
        Set<String> unknownRegions = new TreeSet<>();
        Set<String> unknownHireTypes = new TreeSet<>();
        List<String> selectedNos = new ArrayList<>();
        List<String> failedNos = new ArrayList<>();

        for (Unit u : units) {
            String region = value(u.node(), "SIDO_CD");
            Set<String> sidos = sidoNames.codesOf(region);
            if (sidos.isEmpty()) {
                // 모르는 시도 이름이면 어느 시도 검색에도 나오지 않는다. 설정(sido.regions)에 넣어야 한다
                noRegion++;
                unknownRegions.add(String.valueOf(region));
            }
            Set<String> chips = RecruitChips.hireTypeChips(categories, u.jobType());
            if (chips.isEmpty() && u.jobType() != null) {
                unknownHireTypes.add(u.jobType());
            }
            if (RecruitChips.isEntryOpen(u.employGb())) {
                chips.add(RecruitChips.ENTRY_CHIP);
            }
            if (chips.isEmpty()) {
                // 칩이 없으면 탭을 열어도 결과에 나오지 않는다(D-018). 싣기는 하고 수를 남긴다
                uncategorized++;
            }

            int[] links;
            try {
                long policyId = store.upsert(SOURCE, toRow(u, titles.get(u.no())));
                links = new int[] {
                        store.syncLinks("policy_sido", "sido", policyId, sidos),
                        store.syncLinks("policy_job", "job_code", policyId, Set.of(JobStatus.UNLIMITED_CODE)),
                        store.syncLinks("policy_category", "category_code", policyId, chips) };
            } catch (DataAccessException e) {
                // D-019: 한 행이 실패해도 나머지는 계속한다. 실패한 행은 선정 목록에 없으므로 비활성이 된다
                failedNos.add(u.no());
                log.warn("지방공공기관 행 {} 정규화 실패 — 건너뜀: {}", u.no(), e.getMostSpecificCause().getMessage());
                continue;
            }
            policies++;
            selectedNos.add(u.no());
            sidoLinks += links[0];
            jobLinks += links[1];
            categoryLinks += links[2];
        }

        int maxFailures = Math.max(MIN_FAILURE_LIMIT, rawRows.size() / 100);
        if (failedNos.size() > maxFailures) {
            throw new IllegalStateException("지방공공기관 정규화 실패가 " + failedNos.size() + "건으로 허용치(" + maxFailures
                    + "건)를 넘어 전체를 되돌립니다. 처음 실패한 행: " + failedNos.get(0));
        }
        if (selectedNos.isEmpty()) {
            throw new IllegalStateException("지방공공기관 선정 행이 0건이라 비활성 처리를 하지 않고 중단합니다. "
                    + "성공한 수집(collect_run 의 cleaneye SUCCESS)이 있는지, policy_raw 가 비어 있지 않은지 확인하십시오.");
        }

        // D-019 "12/8 추가": 바꾸기 전에 몇 건이 꺼질지 세고, 상한을 넘으면 바꾸지 않고 멈춘다
        int toDeactivate = store.countToDeactivate(SOURCE, selectedNos);
        if (allowMassDeactivation) {
            if (toDeactivate > MassDeactivationGuard.limitFor(activeBefore)) {
                log.warn("지방공공기관 대량 비활성 강제 반영 — 활성 {}건 중 {}건 비활성 (상한 {}건)",
                        activeBefore, toDeactivate, MassDeactivationGuard.limitFor(activeBefore));
            }
        } else {
            MassDeactivationGuard.check(SOURCE, activeBefore, toDeactivate);
        }
        int deactivated = store.deactivateOthers(SOURCE, selectedNos);

        if (!unknownRegions.isEmpty()) {
            log.warn("지방공공기관 시도 매핑에 없는 이름 {}개 ({}행): {}", unknownRegions.size(), noRegion, unknownRegions);
        }
        if (!unknownHireTypes.isEmpty()) {
            log.warn("지방공공기관 묶음표에 없는 고용형태 {}개: {}", unknownHireTypes.size(), unknownHireTypes);
        }
        if (overlap.sameOrg() > 0) {
            log.warn("잡알리오와 기관 이름이 같은 지방공공기관 행 {}건 · (기관, 제목)까지 같은 행 {}건 — 합치지 않음. 예: {}",
                    overlap.sameOrg(), overlap.sameOrgTitle(), overlap.examples());
        } else {
            log.info("잡알리오와 기관 이름이 같은 지방공공기관 행 0건");
        }
        log.info("지방공공기관 정규화 완료 — policy={} sido={} job={} category={} 지역없음={} 분류없음={} 번호없음={} 비상임={} "
                        + "failed={} deactivated={} ({}ms)",
                policies, sidoLinks, jobLinks, categoryLinks, noRegion, uncategorized, skipped, excluded,
                failedNos.size(), deactivated, System.currentTimeMillis() - startedMs);
        return new NormalizeResult(rawRows.size(), policies, sidoLinks, jobLinks, categoryLinks,
                uncategorized, skipped + excluded, failedNos.size(), deactivated, 0);
    }

    /** 원본 한 행을 policy 행 값으로 바꾼다. */
    PolicyRow toRow(Unit u, String title) {
        JsonNode n = u.node();
        LocalDate start = date(value(n, "PUB_DATE"));
        LocalDate end = date(value(n, "PUB_END_DATE"));
        boolean openEnded = end == null && parse(value(n, "PUB_END_DATE")) != null;
        String period = null;
        if (start != null && openEnded) {
            period = start.format(YYYYMMDD) + " ~ 상시";
        } else if (start != null && end != null) {
            period = start.format(YYYYMMDD) + " ~ " + end.format(YYYYMMDD);
        } else if (end != null) {
            period = "~ " + end.format(YYYYMMDD);
        }

        String special = value(n, "SPECIAL_ITEM");
        return new PolicyRow(
                u.no(),
                title,
                RecruitChips.CATEGORY_LARGE,
                u.jobType(),
                null,
                null,
                "N",
                null, null, null, null,
                null,
                supportContent(u),
                null,
                value(n, "EXHIBIT"),
                qualification(n),
                special == null || "없음".equals(special) ? null : "우대: " + special,
                period,
                start,
                end,
                httpUrl(value(n, "URL")),
                null,
                u.org(),
                null);
    }

    /** 확인 필요로 띄울 자격 조건: 지역 제한과 자격증. 둘 다 없으면 null(뱃지 없음). */
    String qualification(JsonNode n) {
        StringBuilder out = new StringBuilder();
        String local = localLimit(n);
        if (local != null) {
            out.append("지역 제한: ").append(local).append('\n');
        }
        List<String> licenses = licenses(n);
        if (!licenses.isEmpty()) {
            out.append("필요 자격: ").append(String.join(", ", licenses)).append('\n');
        }
        String body = out.toString().strip();
        return body.isEmpty() ? null : body;
    }

    /**
     * 상세 조회(지원 내용 보기)에 보일 글. 칸에 없는 채용 정보를 사람이 읽을 순서로 모은다(D-029 ②).
     * 접수 방법 · 문의처 · 기타 글은 담당 메일 · 전화가 섞여 있어 넣지 않고 원문에서 보라고 안내한다.
     */
    String supportContent(Unit u) {
        JsonNode n = u.node();
        StringBuilder out = new StringBuilder();
        line(out, "고용형태", u.jobType());
        line(out, "채용 구분", RecruitChips.recruitTypeText(u.employGb()));
        line(out, "직급 · 직위", u.position());
        line(out, "직무 분야", u.field());
        String people = value(n, "EMPLOY_NUM");
        line(out, "채용 인원", people == null || !people.matches("[1-9]\\d*") ? null : people + "명");
        line(out, "연봉", value(n, "YEARINCOME"));
        line(out, "근무 지역", value(n, "SIDO_CD"));
        line(out, "지역 제한", localLimit(n));
        String kind = joinNonNull(" · ", value(n, "ENT_GB"), value(n, "ENT_KIND"));
        line(out, "기관", u.org() == null ? null : (kind == null ? u.org() : u.org() + " (" + kind + ")"));
        if ("Y".equalsIgnoreCase(value(n, "RE_MAN_YN"))) {
            line(out, "대체인력", "예");
        }
        String status = value(n, "STATUS");
        line(out, "모집 상태", "모집예정".equals(status) ? "모집예정 (접수 시작 전)" : status);
        line(out, "접수 방법 · 문의", "클린아이 공고 원문에서 확인");
        List<String> licenses = licenses(n);
        section(out, "필요 자격", licenses.isEmpty() ? null : String.join("\n", licenses));
        section(out, "직무 내용", value(n, "DUTY_DETAIL"));
        section(out, "전형 방법", value(n, "JUDGE_METHOD"));
        section(out, "제출 서류", value(n, "EXHIBIT"));
        String special = value(n, "SPECIAL_ITEM");
        section(out, "우대", special == null || "없음".equals(special) ? null : special);
        String body = out.toString().strip();
        return body.isEmpty() ? null : body;
    }

    /**
     * 같은 (기관, 제목)이 여러 행이면 제목 뒤에 그 공고 안에서 달라지는 값을 붙인다. 행이 하나면 제목 그대로.
     * 붙이는 값: 직급 · 직무 분야 · 고용형태 · 채용 구분 중 그 공고 안에서 달라지는 앞의 두 개. 그래도 같으면 번호 순 (n).
     */
    static Map<String, String> displayTitles(List<Unit> units) {
        Map<String, List<Unit>> groups = new LinkedHashMap<>();
        for (Unit u : units) {
            groups.computeIfAbsent(u.org() + "\n" + u.title(), k -> new ArrayList<>()).add(u);
        }
        Map<String, String> titles = new HashMap<>();
        for (List<Unit> group : groups.values()) {
            String base = group.get(0).title() == null ? "" : group.get(0).title();
            if (group.size() == 1) {
                titles.put(group.get(0).no(), cut(base));
                continue;
            }
            List<Integer> varying = new ArrayList<>();
            for (int f = 0; f < 4 && varying.size() < 2; f++) {
                Set<String> values = new HashSet<>();
                for (Unit u : group) {
                    values.add(String.valueOf(u.part(f)));
                }
                if (values.size() > 1) {
                    varying.add(f);
                }
            }
            Map<String, List<Unit>> byTitle = new LinkedHashMap<>();
            for (Unit u : group) {
                List<String> parts = new ArrayList<>();
                for (int f : varying) {
                    if (u.part(f) != null) {
                        parts.add(u.part(f));
                    }
                }
                String t = parts.isEmpty() ? base : base + " · " + String.join(" · ", parts);
                byTitle.computeIfAbsent(t, k -> new ArrayList<>()).add(u);
            }
            byTitle.forEach((t, same) -> {
                if (same.size() == 1) {
                    titles.put(same.get(0).no(), cut(t));
                    return;
                }
                same.sort(Comparator.comparing(Unit::no, CleaneyeNormalizer::compareNo));
                for (int i = 0; i < same.size(); i++) {
                    String suffix = " (" + (i + 1) + ")";
                    titles.put(same.get(i).no(), cut(t, suffix));
                }
            });
        }
        return titles;
    }

    /** 마감 전 잡알리오 공고와 기관 이름 · (기관, 제목)이 같은 행 수. 이름은 공백 · 법인 표기 · 문장부호를 빼고 비교한다. */
    Overlap overlapWithAlio(List<Unit> units) {
        Set<String> orgs = new HashSet<>();
        Set<String> orgTitles = new HashSet<>();
        for (PolicyStore.OrgTitle ot : store.openOrgTitles(Source.ALIO)) {
            orgs.add(orgKey(ot.org()));
            orgTitles.add(orgKey(ot.org()) + "\n" + titleKey(ot.title()));
        }
        int sameOrg = 0, sameOrgTitle = 0;
        List<String> examples = new ArrayList<>();
        for (Unit u : units) {
            String org = orgKey(u.org());
            if (org.isEmpty() || !orgs.contains(org)) {
                continue;
            }
            sameOrg++;
            if (examples.size() < 5) {
                examples.add(u.org());
            }
            if (orgTitles.contains(org + "\n" + titleKey(u.title()))) {
                sameOrgTitle++;
            }
        }
        return new Overlap(sameOrg, sameOrgTitle, examples);
    }

    record Overlap(int sameOrg, int sameOrgTitle, List<String> examples) {}

    /** 한 행. 제목 구분에 쓰는 값은 part(0 ~ 3): 직급 · 직무 분야 · 고용형태 · 채용 구분 */
    record Unit(String no, JsonNode node, String org, String title, String position, String field, String jobType,
                String employGb) {
        String part(int i) {
            return switch (i) {
                case 0 -> position;
                case 1 -> field;
                case 2 -> jobType;
                default -> employGb;
            };
        }
    }

    /** "(재)양천문화재단" · "재단법인 양천문화재단" → "양천문화재단" */
    static String orgKey(String org) {
        return org == null ? "" : org.replaceAll("\\(재\\)|\\(주\\)|\\(사\\)|㈜|재단법인|사단법인|주식회사", "")
                .replaceAll("[\\s()\\[\\]·.,]", "");
    }

    static String titleKey(String title) {
        return title == null ? "" : title.replaceAll("[\\s()\\[\\]·.,_\\-]", "");
    }

    /** 숫자 번호는 숫자 순, 아니면 글자 순 */
    private static int compareNo(String a, String b) {
        if (a.matches("\\d+") && b.matches("\\d+")) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        return a.compareTo(b);
    }

    private static String cut(String title) {
        return cut(title, "");
    }

    /** 제목이 칸보다 길면 뒤 꼬리(suffix)는 남기고 앞을 자른다. */
    private static String cut(String title, String suffix) {
        int room = TITLE_MAX - suffix.length();
        return (title.length() <= room ? title : title.substring(0, room)) + suffix;
    }

    private String localLimit(JsonNode n) {
        if (!"Y".equalsIgnoreCase(value(n, "LOCAL_YN"))) {
            return null;
        }
        String name = value(n, "LOCAL_NAME");
        String reason = value(n, "LOCAL_REASON");
        String where = name == null ? "있음" : name;
        return reason == null ? where : where + " (" + reason + ")";
    }

    private List<String> licenses(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String v = value(n, "ENT_LICENSE" + i);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static String joinNonNull(String sep, String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + sep + b;
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

    /** http(s) 주소만 남긴다. 화면도 http(s) 가 아닌 주소는 링크로 만들지 않는다. */
    private static String httpUrl(String url) {
        String u = url == null ? null : url.strip();
        return u != null && u.matches("(?i)^https?://\\S+") ? u : null;
    }

    /** YYYY-MM-DD → 날짜. 2050년 이후(상시)나 읽을 수 없는 값은 null. */
    static LocalDate date(String isoDate) {
        LocalDate d = parse(isoDate);
        return d == null || !d.isBefore(OPEN_ENDED_FROM) ? null : d;
    }

    private static LocalDate parse(String isoDate) {
        if (isoDate == null || !isoDate.strip().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(isoDate.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 칸의 글을 꺼내 다듬는다. 엔티티 · 줄바꿈을 정리하고, 비었거나 '-' · 'null' 글자면 null.
     * 원본은 모든 값이 글자다(XML → JSON, CleaneyeCollector).
     */
    private String value(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String raw = v.toString();
        String text = TextCleaner.clean(raw.startsWith("\"") ? objectMapper.readValue(raw, String.class) : raw);
        return text == null || "-".equals(text) || "null".equalsIgnoreCase(text) ? null : text;
    }
}

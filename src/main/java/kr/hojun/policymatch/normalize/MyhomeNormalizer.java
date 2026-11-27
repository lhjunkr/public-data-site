package kr.hojun.policymatch.normalize;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import kr.hojun.policymatch.category.CategoryCatalog;
import kr.hojun.policymatch.collect.MyhomeCollector.Kind;
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
 * 마이홈 공공주택 모집공고 원본(policy_raw, source = myhome)을 검색용 표로 옮긴다 (12/11, D-029).
 *
 * 원본 한 행 = (공고, 주택) 하나이고, raw_json 은 그 주택의 지역 행 배열이다(MyhomeCollector).
 *
 * 칸 옮기기 (docs/erd.md "소스별로 칸에 넣는 값")
 *  - 제목 pblancNm · 기관 suplyInsttNm · 모집 시작/마감 beginDe/endDe · 신청 주소 url
 *  - 지역: 지역 행마다 brtcNm(시도 정식 이름) → 시도 코드(SidoNames), 합집합을 policy_sido 로.
 *    시군구(signguNm)는 매칭에 쓰지 않고(D-027 시도 단위) 지원 내용의 "공급 지역"에 남긴다
 *  - 공급 유형: suplyTyNm(국민임대 · 행복주택 …, 임대만) → category_medium. 분양은 "공공분양"
 *  - 분류: 칩은 주거 · 주택(housing.house) 하나. 공급 유형별 칩은 만들지 않는다(탭당 칩 3개 규칙, 화면 편입은 12/12)
 *  - 나이 · 소득: 원본에 없다(자격은 공고문 PDF). 비워서 지금 매칭 SQL 이 통과시키고, 자격은 '확인 필요'로 둔다(D-001 · D-003)
 *  - 취업 상태: 제한없음(0013010) 한 줄
 *  - 임대료 · 보증금(rentGtn · mtRntchrg)은 아직 싣지 않는다. 단위(원 · 천원)를 실제 공고와 맞춰 보기 전이라,
 *    틀린 금액을 보여 주는 것보다 빼는 편이 낫다. 화면 편입(12/12)에서 확인 후 넣는다
 *
 * 정정공고 (D-029 ⑤ "같은 식별자가 있을 때만")
 *  - 어떤 공고의 beforePblancId 가 다른 공고의 pblancId 를 가리키면, 가리켜진 쪽은 고치기 전 공고다. 싣지 않고 duplicates 로 센다.
 *    이미 실려 있던 이전 공고는 선정 목록에 없으므로 비활성이 된다. 12/5: 정정 81행 중 77행은 이전 공고도 응답에 있었다.
 *  - 임대 · 분양은 번호 체계가 같다는 보장이 없어 같은 구분 안에서만 맞춘다.
 *
 * 선정 · 비활성 · 실패 격리 · 대량 변경 감지는 K-Startup 정규화기와 같은 규칙을 이 출처 안에서만 쓴다(D-014 · D-017 · D-019).
 *  - 승인 상태 칸이 없다. 서버가 접수가 끝나지 않은 공고만 준다.
 *  - 마감은 여기서 거르지 않고 매칭할 때 거른다(D-008). 접수 시작 전 공고(12/5: 임대 117개 중 67개)도 싣는다.
 */
@Service
public class MyhomeNormalizer implements SourceNormalizer {

    private static final Logger log = LoggerFactory.getLogger(MyhomeNormalizer.class);
    private static final Source SOURCE = Source.MYHOME;

    /** 화면 칩: 주거 탭 · 주택 칩 (application.yml 의 category.tabs) */
    static final String CHIP = "housing.house";
    static final String CATEGORY_LARGE = "주거";

    /** extra_qualification 고정 문구. 비어 있지 않으므로 화면에 '추가 자격 확인 필요'가 뜬다(D-003). */
    static final String QUALIFICATION_NOTICE =
            "입주 자격(나이 · 소득 · 자산 · 무주택 여부 등)은 모집공고문에서 확인해야 합니다.";

    /** 이 날 이후의 마감일은 "상시"로 본다(K-Startup 과 같은 기준). */
    static final LocalDate OPEN_ENDED_FROM = LocalDate.of(2099, 1, 1);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** D-019: 정책 단위 실패를 이만큼(또는 후보의 1%)까지는 건너뛰고, 넘으면 전체를 되돌린다. */
    static final int MIN_FAILURE_LIMIT = 10;

    private final PolicyStore store;
    private final SidoNames sidoNames;
    private final ObjectMapper objectMapper;

    /** 칩이 묶음표에서 사라지거나 닫히면 공고가 조용히 검색에서 빠지므로, 앱이 뜰 때 확인한다(K-Startup 과 같은 방식). */
    public MyhomeNormalizer(PolicyStore store, SidoNames sidoNames, ObjectMapper objectMapper,
                            CategoryCatalog categories) {
        if (!categories.openChipCodes().contains(CHIP)) {
            throw new IllegalStateException("마이홈 칩 " + CHIP + " 이(가) 묶음표의 열린 칩에 없습니다. "
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
        List<PolicyStore.RawRow> rawRows = store.rawRowsSinceLastSuccess(SOURCE);

        int skipped = 0;
        List<Unit> units = new ArrayList<>(rawRows.size());
        for (PolicyStore.RawRow raw : rawRows) {
            Unit unit = parse(raw);
            if (unit == null) {
                skipped++;
                continue;
            }
            units.add(unit);
        }
        Set<String> superseded = supersededPosts(units);
        log.info("마이홈 정규화 후보 {}건 · 정정공고가 가리키는 이전 공고 {}개", units.size(), superseded.size());

        int policies = 0, sidoLinks = 0, jobLinks = 0, categoryLinks = 0, noRegion = 0, duplicates = 0;
        Set<String> unknownRegions = new TreeSet<>();
        List<String> selectedNos = new ArrayList<>();
        List<String> failedNos = new ArrayList<>();

        for (Unit unit : units) {
            if (superseded.contains(postKey(unit.kind(), unit.pblancId()))) {
                duplicates++;
                log.info("마이홈 {} 는 정정공고가 가리키는 이전 공고라 싣지 않음", unit.policyNo());
                continue;
            }

            Set<String> sidos = new LinkedHashSet<>();
            for (JsonNode row : unit.rows()) {
                String brtc = TextCleaner.clean(text(row, "brtcNm"));
                Set<String> codes = sidoNames.codesOf(brtc);
                if (codes.isEmpty()) {
                    unknownRegions.add(String.valueOf(brtc));
                }
                sidos.addAll(codes);
            }
            if (sidos.isEmpty()) {
                // 모르는 시도 이름이면 어느 시도 검색에도 나오지 않는다. 설정(sido.regions)에 넣어야 한다
                noRegion++;
            }

            int[] links;
            try {
                long policyId = store.upsert(SOURCE, toRow(unit));
                links = new int[] {
                        store.syncLinks("policy_sido", "sido", policyId, sidos),
                        store.syncLinks("policy_job", "job_code", policyId, Set.of(JobStatus.UNLIMITED_CODE)),
                        store.syncLinks("policy_category", "category_code", policyId, Set.of(CHIP)) };
            } catch (DataAccessException e) {
                // D-019: 한 공고가 실패해도 나머지는 계속한다. 실패한 공고는 선정 목록에 없으므로 비활성이 된다
                failedNos.add(unit.policyNo());
                log.warn("마이홈 {} 정규화 실패 — 건너뜀: {}", unit.policyNo(), e.getMostSpecificCause().getMessage());
                continue;
            }
            policies++;
            selectedNos.add(unit.policyNo());
            sidoLinks += links[0];
            jobLinks += links[1];
            categoryLinks += links[2];
        }

        int maxFailures = Math.max(MIN_FAILURE_LIMIT, rawRows.size() / 100);
        if (failedNos.size() > maxFailures) {
            throw new IllegalStateException("마이홈 정규화 실패가 " + failedNos.size() + "건으로 허용치(" + maxFailures
                    + "건)를 넘어 전체를 되돌립니다. 처음 실패한 공고: " + failedNos.get(0));
        }
        if (selectedNos.isEmpty()) {
            throw new IllegalStateException("마이홈 선정 공고가 0건이라 비활성 처리를 하지 않고 중단합니다. "
                    + "성공한 수집(collect_run 의 myhome SUCCESS)이 있는지, policy_raw 가 비어 있지 않은지 확인하십시오.");
        }

        // D-019 "12/8 추가": 바꾸기 전에 몇 건이 꺼질지 세고, 상한을 넘으면 바꾸지 않고 멈춘다
        int toDeactivate = store.countToDeactivate(SOURCE, selectedNos);
        if (allowMassDeactivation) {
            if (toDeactivate > MassDeactivationGuard.limitFor(activeBefore)) {
                log.warn("마이홈 대량 비활성 강제 반영 — 활성 {}건 중 {}건 비활성 (상한 {}건)",
                        activeBefore, toDeactivate, MassDeactivationGuard.limitFor(activeBefore));
            }
        } else {
            MassDeactivationGuard.check(SOURCE, activeBefore, toDeactivate);
        }
        int deactivated = store.deactivateOthers(SOURCE, selectedNos);

        if (!unknownRegions.isEmpty()) {
            log.warn("마이홈 시도 매핑에 없는 시도 이름 {}개(시도 연결 없는 공고 {}건): {}", unknownRegions.size(), noRegion,
                    unknownRegions);
        }
        log.info("마이홈 정규화 완료 — policy={} sido={} job={} category={} 지역없음={} skipped={} failed={} "
                        + "duplicates(정정 전 공고)={} deactivated={} ({}ms)",
                policies, sidoLinks, jobLinks, categoryLinks, noRegion, skipped, failedNos.size(), duplicates,
                deactivated, System.currentTimeMillis() - startedMs);
        // uncategorized: 마이홈은 칩을 늘 붙이므로 0
        return new NormalizeResult(rawRows.size(), policies, sidoLinks, jobLinks, categoryLinks,
                0, skipped, failedNos.size(), deactivated, duplicates);
    }

    /** 원본 한 행을 (구분, 공고 번호, 지역 행들)로 읽는다. 읽을 수 없으면 null. */
    Unit parse(PolicyStore.RawRow raw) {
        Kind kind = Kind.ofPolicyNo(raw.policyNo());
        if (kind == null) {
            log.warn("마이홈 원본 번호 {} 의 접두어(rent- · sale-)를 몰라 건너뜁니다.", raw.policyNo());
            return null;
        }
        JsonNode root = objectMapper.readTree(raw.rawJson());
        List<JsonNode> rows = new ArrayList<>();
        if (root.isArray()) {
            for (int i = 0; i < root.size(); i++) {
                rows.add(root.get(i));
            }
        } else if (root.isObject()) {
            rows.add(root);
        }
        if (rows.isEmpty()) {
            return null;
        }
        String pblancId = text(rows.get(0), "pblancId");
        if (pblancId == null) {
            return null;
        }
        return new Unit(raw.policyNo(), kind, pblancId.strip(), rows);
    }

    /** 정정공고들이 가리키는 이전 공고(구분 + pblancId). 자기 자신을 가리키는 값은 무시한다. */
    Set<String> supersededPosts(List<Unit> units) {
        Set<String> out = new HashSet<>();
        for (Unit unit : units) {
            for (JsonNode row : unit.rows()) {
                String before = text(row, "beforePblancId");
                if (before != null && !before.strip().equals(unit.pblancId())) {
                    out.add(postKey(unit.kind(), before.strip()));
                }
            }
        }
        return out;
    }

    private static String postKey(Kind kind, String pblancId) {
        return kind.prefix() + ":" + pblancId;
    }

    /** (공고, 주택) 하나를 policy 행 값으로 바꾼다. 공고 공통 칸은 첫 지역 행에서 읽는다. */
    PolicyRow toRow(Unit unit) {
        JsonNode first = unit.rows().get(0);
        String startText = digits(text(first, "beginDe"));
        String endText = digits(text(first, "endDe"));
        LocalDate start = date(startText);
        LocalDate end = date(endText);
        String period = null;
        if (start != null && end == null && rawDate(endText) != null) {
            period = startText + " ~ 상시";
        } else if (start != null && end != null) {
            period = startText + " ~ " + endText;
        } else if (end != null) {
            period = "~ " + endText;
        }

        return new PolicyRow(
                unit.policyNo(),
                TextCleaner.clean(text(first, "pblancNm")),
                CATEGORY_LARGE,
                supplyType(unit),
                null,
                null,
                "N",
                null, null, null, null,
                null,
                supportContent(unit),
                null,
                null,
                QUALIFICATION_NOTICE,
                null,
                period,
                start,
                end,
                httpUrl(text(first, "url")),
                null,
                TextCleaner.clean(text(first, "suplyInsttNm")),
                null);
    }

    /** 공급 유형: 임대는 suplyTyNm(국민임대 · 행복주택 …), 없으면 구분 이름(공공임대 · 공공분양). */
    String supplyType(Unit unit) {
        String type = TextCleaner.clean(text(unit.rows().get(0), "suplyTyNm"));
        return type != null ? type : unit.kind().label();
    }

    /**
     * 상세 조회(지원 내용 보기)에 보일 글. 표 칸에 없는 공고 정보를 사람이 읽을 순서로 모은다(D-029 ②).
     * 지역 행이 여러 개면 "공급 지역"에 시군구와 공급 호수를 한 줄씩 적는다.
     */
    String supportContent(Unit unit) {
        JsonNode first = unit.rows().get(0);
        StringBuilder out = new StringBuilder();
        out.append("안내: ").append(QUALIFICATION_NOTICE).append('\n');
        line(out, "구분", unit.kind().label());
        line(out, "공급 유형", supplyType(unit));
        line(out, "주택 유형", distinct(unit, "houseTyNm"));
        line(out, "공고 상태", distinct(unit, "sttusNm"));
        line(out, "공급 기관", text(first, "suplyInsttNm"));

        Integer total = 0;
        List<String> regions = new ArrayList<>();
        for (JsonNode row : unit.rows()) {
            String brtc = TextCleaner.clean(text(row, "brtcNm"));
            String signgu = TextCleaner.clean(text(row, "signguNm"));
            String place = brtc == null ? "(지역 없음)" : (signgu == null ? brtc + " 전체" : brtc + " " + signgu);
            Integer count = number(text(row, "sumSuplyCo"));
            if (count == null) {
                total = null;
            } else if (total != null) {
                total += count;
            }
            String entry = count == null ? place : place + " · " + count + "호";
            if (!regions.contains(entry)) {
                regions.add(entry);
            }
        }
        if (total != null && total > 0) {
            line(out, "공급 호수", "합계 " + total + "호");
        }
        out.append('\n').append("[공급 지역]\n");
        for (String r : regions) {
            out.append(r).append('\n');
        }
        return out.toString().strip();
    }

    /** 지역 행들에서 한 칸의 서로 다른 값을 순서대로 이어 붙인다. 보통 하나다. */
    private String distinct(Unit unit, String field) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode row : unit.rows()) {
            String v = TextCleaner.clean(text(row, field));
            if (v != null) {
                values.add(v);
            }
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static void line(StringBuilder out, String label, String value) {
        String v = TextCleaner.clean(value);
        if (v != null) {
            out.append(label).append(": ").append(v).append('\n');
        }
    }

    private static Integer number(String text) {
        if (text == null || !text.strip().matches("\\d{1,9}")) {
            return null;
        }
        return Integer.parseInt(text.strip());
    }

    /** http(s) 주소만 남긴다. 화면도 http(s) 가 아닌 주소는 링크로 만들지 않는다. */
    private static String httpUrl(String url) {
        String u = url == null ? null : url.strip();
        return u != null && u.matches("(?i)^https?://.+") ? u : null;
    }

    /** "2026-12-01" · "2026.12.01" 처럼 와도 숫자만 남긴다. 원본은 YYYYMMDD 다(12/5). */
    private static String digits(String text) {
        return text == null ? null : text.replaceAll("[^0-9]", "");
    }

    /** YYYYMMDD → 날짜. 2099년 이후(상시)나 읽을 수 없는 값은 null. */
    static LocalDate date(String yyyymmdd) {
        LocalDate d = rawDate(yyyymmdd);
        return d == null || !d.isBefore(OPEN_ENDED_FROM) ? null : d;
    }

    private static LocalDate rawDate(String yyyymmdd) {
        if (yyyymmdd == null || !yyyymmdd.matches("\\d{8}")) {
            return null;
        }
        try {
            return LocalDate.parse(yyyymmdd, YYYYMMDD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 칸의 글을 JSON 이스케이프를 푼 그대로 꺼낸다. 숫자 칸(houseSn · sumSuplyCo)은 숫자 그대로의 글자.
     * Jackson 2 → 3 에서 값 추출 메서드 이름이 바뀌어, 버전과 상관없는 toString() + readValue 로 꺼낸다(K-Startup 과 같은 이유).
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

    /** 원본 한 행을 읽은 것: 번호 · 임대/분양 · 공고 번호 · 지역 행들 */
    record Unit(String policyNo, Kind kind, String pblancId, List<JsonNode> rows) {}
}

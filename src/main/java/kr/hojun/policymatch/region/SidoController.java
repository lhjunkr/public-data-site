package kr.hojun.policymatch.region;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/regions")
public class SidoController {

    private static final Map<String, String> SIDO_NAMES = Map.ofEntries(
            Map.entry("11", "서울"), Map.entry("12", "전남광주"),
            Map.entry("26", "부산"), Map.entry("27", "대구"),
            Map.entry("28", "인천"), Map.entry("30", "대전"),
            Map.entry("31", "울산"), Map.entry("36", "세종"),
            Map.entry("41", "경기"), Map.entry("43", "충북"),
            Map.entry("44", "충남"), Map.entry("47", "경북"),
            Map.entry("48", "경남"), Map.entry("50", "제주"),
            Map.entry("51", "강원"), Map.entry("52", "전북"));

    private final SidoRepository sidoRepository;

    public SidoController(SidoRepository sidoRepository) {
        this.sidoRepository = sidoRepository;
    }

    @GetMapping("/sido")
    public List<SidoResponse> list() {
        return sidoRepository.findAllSidoCodes().stream()
                .map(code -> new SidoResponse(code, SIDO_NAMES.getOrDefault(code, code)))
                .toList();
    }
}
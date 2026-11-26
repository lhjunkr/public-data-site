package kr.hojun.policymatch.region;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/regions")
public class SidoController {

    private final SidoRepository sidoRepository;
    private final SidoNames sidoNames;

    /** 시도 이름은 설정(application.yml 의 sido.regions)의 첫 이름이다. 수집 정규화가 쓰는 표와 같은 것을 쓴다(D-029 ⑥). */
    public SidoController(SidoRepository sidoRepository, SidoNames sidoNames) {
        this.sidoRepository = sidoRepository;
        this.sidoNames = sidoNames;
    }

    @GetMapping("/sido")
    public List<SidoResponse> list() {
        return sidoRepository.findAllSidoCodes().stream()
                .map(code -> new SidoResponse(code, sidoNames.displayName(code).orElse(code)))
                .toList();
    }
}
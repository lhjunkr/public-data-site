package kr.hojun.policymatch.job;

import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 화면의 취업 상태 선택 상자를 채우는 목록 (D-021). 시도 목록 API 와 같은 모양 {code, name}. */
@RestController
@RequestMapping("/api/v1/job-statuses")
public class JobStatusController {

    @GetMapping
    public List<JobStatusResponse> list() {
        return Arrays.stream(JobStatus.values())
                .map(s -> new JobStatusResponse(s.code(), s.label()))
                .toList();
    }
}

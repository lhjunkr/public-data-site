// 매칭 검색 부하 측정 (11/19 · D-027 근거). 동시 사용자(VU) 수만큼 쉬지 않고 검색을 반복한다.
// 실행 (맥, Docker): docker run --rm -i grafana/k6 run --vus 5 --duration 30s - < scripts/load-match.js
// 다른 주소: docker run --rm -i -e BASE_URL=http://localhost:8080 grafana/k6 run ... (앱 서버 안 등)
// VU 하나 = 검색 버튼을 쉬지 않고 누르는 사람 한 명. 같은 VU 는 연결을 재사용한다(브라우저와 같음).
//   scripts/measure.sh 는 요청마다 새로 연결하므로 한 번 측정값이 이것보다 연결 · 암호화(약 150ms)만큼 길다.
import http from 'k6/http';
import { check } from 'k6';

const URL = (__ENV.BASE_URL || 'https://chajaga.com') + '/api/v1/policies/match';
const BODY = JSON.stringify({ age: 25, sido: '11' }); // scripts/match-25-seoul.json 과 같은 조건
const PARAMS = {
  headers: { 'Content-Type': 'application/json', 'Accept-Encoding': 'gzip, deflate, br' },
  timeout: '30s',
};

export const options = {
  discardResponseBodies: true, // 받기는 다 받되 내용을 보관하지 않는다(메모리 절약)
  summaryTrendStats: ['med', 'p(95)', 'max'],
};

export default function () {
  const res = http.post(URL, BODY, PARAMS);
  check(res, { 'status 200': (r) => r.status === 200 });
}

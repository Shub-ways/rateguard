// Capacity benchmark: closed-loop constant-VU load against the demo endpoint.
// One distinct API key per VU. The rate limit is configured high enough that
// 429s should not occur; any that do are counted, never treated as success.
//
// Env: TARGET (base URL), VUS, DURATION, KEYS (';'-separated), SUMMARY_FILE, LABEL
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const status200 = new Counter('status_200');
const status429 = new Counter('status_429');
const status503 = new Counter('status_503');
const statusOther = new Counter('status_other');
const networkErrors = new Counter('network_errors');

// ';'-separated: k6 splits -e values on commas.
const KEYS = (__ENV.KEYS || '').split(';').filter((k) => k.length > 0);
const URL = `${__ENV.TARGET}/api/v1/demo/resource`;

export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '30s',
      gracefulStop: '5s',
    },
  },
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const key = KEYS.length > 0 ? KEYS[(__VU - 1) % KEYS.length] : 'none';
  const res = http.get(URL, { headers: { 'X-API-Key': key } });
  if (res.status === 200) status200.add(1);
  else if (res.status === 429) status429.add(1);
  else if (res.status === 503) status503.add(1);
  else if (res.status === 0) networkErrors.add(1);
  else statusOther.add(1);
}

export function handleSummary(data) {
  data.rateguard = { label: __ENV.LABEL || '', target: __ENV.TARGET, vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION };
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const d = m.http_req_duration.values;
  const line = `${__ENV.LABEL}: reqs=${m.http_reqs.values.count} rps=${m.http_reqs.values.rate.toFixed(1)} ` +
    `200=${count('status_200')} 429=${count('status_429')} 503=${count('status_503')} other=${count('status_other')} ` +
    `neterr=${count('network_errors')} p50=${d.med.toFixed(2)}ms p95=${d['p(95)'].toFixed(2)}ms p99=${d['p(99)'].toFixed(2)}ms\n`;
  return { [__ENV.SUMMARY_FILE]: JSON.stringify(data, null, 1), stdout: line };
}

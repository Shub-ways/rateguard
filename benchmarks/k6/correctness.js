// Distributed quota correctness under load (NOT a throughput benchmark).
// ITERATIONS requests from one client (one API key), spread over VUS virtual
// users through nginx to two app instances sharing Redis. With quota LIMIT per
// window, exactly LIMIT requests may be accepted.
//
// Env: TARGET, KEY, VUS, ITERATIONS, LIMIT, SUMMARY_FILE, LABEL
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const status200 = new Counter('status_200');
const status429 = new Counter('status_429');
const status503 = new Counter('status_503');
const statusOther = new Counter('status_other');
const networkErrors = new Counter('network_errors');
const servedByA = new Counter('served_by_app_a');
const servedByB = new Counter('served_by_app_b');
const servedByUnknown = new Counter('served_by_unknown');
const acceptedByA = new Counter('accepted_by_app_a');
const acceptedByB = new Counter('accepted_by_app_b');

const URL = `${__ENV.TARGET}/api/v1/demo/resource`;

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 50),
      iterations: Number(__ENV.ITERATIONS || 1000),
      maxDuration: '50s',
    },
  },
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

function header(res, name) {
  const wanted = name.toLowerCase();
  for (const k of Object.keys(res.headers)) {
    if (k.toLowerCase() === wanted) return res.headers[k];
  }
  return undefined;
}

export default function () {
  const res = http.get(URL, { headers: { 'X-API-Key': __ENV.KEY } });
  if (res.status === 0) {
    networkErrors.add(1);
    return;
  }
  const instance = header(res, 'X-RateGuard-Instance');
  if (instance === 'app-a') servedByA.add(1);
  else if (instance === 'app-b') servedByB.add(1);
  else servedByUnknown.add(1);

  if (res.status === 200) {
    status200.add(1);
    if (instance === 'app-a') acceptedByA.add(1);
    if (instance === 'app-b') acceptedByB.add(1);
  } else if (res.status === 429) status429.add(1);
  else if (res.status === 503) status503.add(1);
  else statusOther.add(1);
}

export function handleSummary(data) {
  const c = (n) => (data.metrics[n] ? data.metrics[n].values.count : 0);
  const limit = Number(__ENV.LIMIT || 100);
  data.rateguard = {
    label: __ENV.LABEL || '',
    limit,
    iterations: Number(__ENV.ITERATIONS || 1000),
    accepted: c('status_200'),
    rejected: c('status_429'),
    status503: c('status_503'),
    otherStatuses: c('status_other'),
    networkErrors: c('network_errors'),
    servedBy: { 'app-a': c('served_by_app_a'), 'app-b': c('served_by_app_b'), unknown: c('served_by_unknown') },
    acceptedBy: { 'app-a': c('accepted_by_app_a'), 'app-b': c('accepted_by_app_b') },
    quotaViolated: c('status_200') > limit,
  };
  const r = data.rateguard;
  const line = `${r.label}: accepted=${r.accepted} rejected=${r.rejected} 503=${r.status503} other=${r.otherStatuses} ` +
    `neterr=${r.networkErrors} servedBy=${JSON.stringify(r.servedBy)} acceptedBy=${JSON.stringify(r.acceptedBy)} ` +
    `quotaViolated=${r.quotaViolated}\n`;
  return { [__ENV.SUMMARY_FILE]: JSON.stringify(data, null, 1), stdout: line };
}

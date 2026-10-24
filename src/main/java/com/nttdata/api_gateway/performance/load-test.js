
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 100,
    duration: '30s',
};

export default function () {
    const url = 'http://localhost:8080/api/customers';

    const response = http.get(url);

    check(response, {
        'status es 200': (r) => r.status === 200,
        'respuesta menor a 500 ms': (r) => r.timings.duration < 500,
    });

    if (response.status !== 200) {
        console.error(
            `Status: ${response.status} | ` +
            `Error: ${response.error || 'Sin error de transporte'} | ` +
            `URL: ${response.url}`
        );
    }

    sleep(1);
}

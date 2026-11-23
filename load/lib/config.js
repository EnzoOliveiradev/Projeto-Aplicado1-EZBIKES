// Configuração comum dos testes de carga.
// Dentro do compose a API é http://api:8080; fora dele, use -e BASE_URL=http://localhost:8080.
export const BASE_URL = __ENV.BASE_URL || 'http://api:8080';
export const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || '';

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

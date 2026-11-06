import axios from 'axios';
import {getApiBase} from './config.js';

const instance = axios.create({
    withCredentials: true,
    baseURL: getApiBase()
});

// Local accounts (kockpit-backend-authentication-local): both answer {username, groups} and set the cookie.
export const signup = async (email, password) => (await instance.post('/auth/signup', {email, password})).data;

export const signin = async (email, password) => (await instance.post('/auth/login', {email, password})).data;

// Tenants and API keys (kockpit-backend-tenants)
export const getTenants = async () => (await instance.get('/tenants')).data;

export const createTenant = async (slug, envs) => (await instance.post('/tenants', {slug, envs})).data;

// the only response that holds the key itself
export const createApiKey = async (slug, name) =>
    (await instance.post(`/tenants/${encodeURIComponent(slug)}/api-keys`, {name})).data;

export const revokeApiKey = async (slug, id) =>
    instance.delete(`/tenants/${encodeURIComponent(slug)}/api-keys/${encodeURIComponent(id)}`);

export const getAllTenants = async () => (await instance.get('/admin/tenants')).data;

export const setApiKeyQuota = async (id, dailyQuota) =>
    (await instance.put(`/admin/api-keys/${encodeURIComponent(id)}/quota`, {dailyQuota})).data;

// The backend's explanation of a refused call ({error, message}), else the HTTP error.
export const reasonOf = (err) => err?.response?.data?.message || err?.message || 'Unknown error';

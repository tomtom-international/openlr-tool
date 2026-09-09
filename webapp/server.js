const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const API_URL = process.env.API_URL || 'http://app:8081';

// Serve static files from the public directory.
//
// `etag` plus `maxAge: 0` makes the browser revalidate on every load and get a 304
// when nothing changed. That replaces the hand-incremented `app.js?v=N` query
// parameter, which had to be remembered on every edit and silently served stale
// JavaScript when it was not.
app.use(express.static(path.join(__dirname, 'public'), {
    etag: true,
    lastModified: true,
    maxAge: 0,
    cacheControl: true,
}));

// Proxy API requests to the Spring Boot backend
app.use('/api', createProxyMiddleware({
    target: API_URL,
    changeOrigin: true,
    logLevel: 'info',
    onError: (err, req, res) => {
        console.error('Proxy error:', err);
        res.status(500).json({
            error: 'Proxy error',
            message: 'Failed to connect to OpenLR API backend',
            details: err.message
        });
    }
}));

// Health check endpoint
app.get('/health', (req, res) => {
    res.json({ status: 'ok', service: 'openlr-webapp' });
});

// Catch-all route to serve index.html for SPA
app.get('*', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, '0.0.0.0', () => {
    console.log(`OpenLR Webapp running on port ${PORT}`);
    console.log(`Proxying API requests to: ${API_URL}`);
});

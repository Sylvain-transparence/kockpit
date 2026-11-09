# Kockpit TypeScript audit SDK

`@kockpit/audit` creates and correlates Kockpit audit reports in Node.js.
It groups events into spans, supports filtered HTTP events, and sends reports
through an application-provided transport. The package is private while its
integration in Kockpit is being evaluated.

Run the checks from this directory with Node.js 22 or 24 and pnpm 11.24.0.

```sh
pnpm install --frozen-lockfile
pnpm build
pnpm test
```

The tests use an in-memory transport and synthetic reports. They cover
serialization with and without gzip, request context isolation, event limits
and failure handling.

# Berth 9 console

Angular 21 operator console for the Berth 9 integration engine: live pipeline, files, exceptions, mapping studio and partners.

```bash
npm ci
npm start                 # dev server on http://localhost:4200; add ?api=http://localhost:8080 for a live backend
npm run build             # production build in dist/berth9-console/browser
```

Data source is picked at startup:

- `?api=http://localhost:8080` or served by the backend itself: live REST + Server-Sent Events.
- Otherwise (the hosted demo): a recorded run of the real backend from `public/replay/replay.json`. `?mode=replay` forces it.

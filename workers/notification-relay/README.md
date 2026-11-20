# Lore notification relay (Cloudflare Workers Free)

This replaces the Firebase Cloud Function trigger. It does not require Firebase Blaze. The Worker receives an event from the Android app, verifies its Firebase ID token, reads only that user's notification preferences and registered FCM devices from Firestore, then sends data-only FCM messages.

## Deploy

1. Create a Cloudflare account and stay on the Workers Free plan.
2. In Google Cloud Console for Firebase project `lore-digital-library`, create a dedicated service account for the relay. Grant only these roles to the service account: **Cloud Datastore Viewer** (\`roles/datastore.viewer\`) for reading the user's Firestore settings/device tokens, and **Firebase Cloud Messaging API Admin** (\`roles/firebasecloudmessaging.admin\`) for sending FCM messages. Do not use an Owner account key.
3. Enable the Firebase Cloud Messaging API for the project.
4. Install Wrangler locally and run `npx wrangler secret put FIREBASE_PROJECT_ID`, `GOOGLE_CLIENT_EMAIL`, and `GOOGLE_PRIVATE_KEY`. For the project ID use `lore-digital-library`. Paste the private key only into Cloudflare's secret prompt, never into source control or ChatGPT.
5. Run `npm install` and `npx wrangler deploy` from this directory. Copy the resulting `https://...workers.dev` URL.
6. Add that URL as the GitHub Actions secret or repository variable `LORE_NOTIFICATION_RELAY_URL` (or supply it as the Gradle property/environment variable when building). Rebuild Lore.

## Cost and limits

Keep the Cloudflare account on its Free plan. The free tier has request and CPU limits; this design makes one request per notification event and does not use a paid Firebase function. FCM itself remains free. Google Cloud service-account API access is not a guarantee of zero billing if other paid Google Cloud services are enabled, so keep this service account restricted and monitor the project's usage.

## Security notes

- The Worker rejects requests without a valid Firebase ID token and derives the recipient UID from the verified token, never from the request body.
- The service-account private key belongs only in Cloudflare Worker secrets.
- Firestore security rules remain user-scoped; this Worker uses its narrowly permissioned service account.
- Do not expose the service-account key in the Android app, GitHub source, APK, or a public issue.

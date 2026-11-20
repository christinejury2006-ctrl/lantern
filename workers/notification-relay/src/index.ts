import { createRemoteJWKSet, importPKCS8, jwtVerify, SignJWT } from "jose";

interface Env {
  FIREBASE_PROJECT_ID: string;
  GOOGLE_CLIENT_EMAIL: string;
  GOOGLE_PRIVATE_KEY: string;
}
interface Notice {
  eventId: string; category: string; title?: string; body?: string; target?: string;
}
const jwks = createRemoteJWKSet(new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"));
const allowedCategories = new Set(["library", "sync", "downloads", "account", "recommendations", "reminders"]);
let cachedGoogleToken: { value: string; expiresAt: number } | undefined;
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { "content-type": "application/json", "cache-control": "no-store" }
});
const field = (fields: Record<string, any>, key: string) => fields?.[key]?.stringValue;
const boolField = (fields: Record<string, any>, key: string, fallback: boolean) =>
  typeof fields?.[key]?.booleanValue === "boolean" ? fields[key].booleanValue : fallback;

async function googleAccessToken(env: Env): Promise<string> {
  if (cachedGoogleToken && cachedGoogleToken.expiresAt > Date.now() + 60_000) return cachedGoogleToken.value;
  const pem = env.GOOGLE_PRIVATE_KEY.replace(/\\n/g, "\n");
  const key = await importPKCS8(pem, "RS256");
  const now = Math.floor(Date.now() / 1000);
  const assertion = await new SignJWT({ scope: "https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore" })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(env.GOOGLE_CLIENT_EMAIL)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now).setExpirationTime(now + 3600).sign(key);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion })
  });
  if (!response.ok) throw new Error("Google service authentication failed.");
  const data = await response.json() as { access_token?: string };
  if (!data.access_token) throw new Error("Google did not return an access token.");
  cachedGoogleToken = { value: data.access_token, expiresAt: Date.now() + 3_500_000 };
  return data.access_token;
}

async function firestoreGet(url: string, token: string) {
  const response = await fetch(url, { headers: { authorization: `Bearer ${token}` } });
  if (!response.ok) throw new Error(`Firestore request failed (${response.status}).`);
  return response.json() as Promise<any>;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
    const auth = request.headers.get("authorization") ?? "";
    if (!auth.startsWith("Bearer ")) return json({ error: "Authentication required" }, 401);

    let uid: string;
    try {
      const verified = await jwtVerify(auth.slice(7), jwks, {
        issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`,
        audience: env.FIREBASE_PROJECT_ID
      });
      if (verified.payload.firebase === undefined || typeof verified.payload.sub !== "string") {
        return json({ error: "Invalid Firebase identity" }, 401);
      }
      uid = verified.payload.sub;
    } catch {
      return json({ error: "Invalid or expired Firebase token" }, 401);
    }

    let notice: Notice;
    try { notice = await request.json() as Notice; }
    catch { return json({ error: "Invalid JSON" }, 400); }
    if (!notice.eventId || !allowedCategories.has(notice.category)) return json({ error: "Invalid notification event" }, 400);

    try {
      const token = await googleAccessToken(env);
      const base = `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents`;
      const settings = await firestoreGet(`${base}/users/${uid}/notificationSettings/default`, token).catch(() => ({}));
      const settingsFields = settings.fields ?? {};
      if (!boolField(settingsFields, "enabled", true) || !boolField(settingsFields, "pushEnabled", true)) {
        return json({ delivered: 0, skipped: true });
      }
      const categoryMap = settingsFields.categories?.mapValue?.fields ?? {};
      if (categoryMap[notice.category]?.booleanValue === false) return json({ delivered: 0, skipped: true });

      const devices = await firestoreGet(`${base}/users/${uid}/devices?pageSize=100`, token);
      const docs = Array.isArray(devices.documents) ? devices.documents : [];
      const tokens = docs.map((doc: any) => ({ name: doc.name, token: field(doc.fields ?? {}, "token") }))
        .filter((item: any) => typeof item.token === "string" && item.token.length > 0);
      let delivered = 0;
      for (const device of tokens) {
        const response = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
          method: "POST",
          headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
          body: JSON.stringify({ message: { token: device.token, android: { priority: "HIGH" }, data: {
            event_id: notice.eventId, recipient_uid: uid, category: notice.category,
            title: notice.title || "Lore update", body: notice.body || "", target: notice.target || ""
          } } })
        });
        if (response.ok) delivered++;
        else {
          const errorText = await response.text();
          if (errorText.includes("UNREGISTERED") || errorText.includes("registration-token-not-registered")) {
            await fetch(`https://firestore.googleapis.com/v1/${device.name}`, {
              method: "DELETE", headers: { authorization: `Bearer ${token}` }
            });
          }
        }
      }
      return json({ delivered });
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : "Notification delivery failed" }, 502);
    }
  }
};

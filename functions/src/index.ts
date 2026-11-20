import { initializeApp } from "firebase-admin/app";
import { DocumentReference, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentCreated } from "firebase-functions/v2/firestore";

initializeApp();

type NoticeEvent = {
  eventId?: string;
  category?: string;
  title?: string;
  body?: string;
  target?: string;
};

/**
 * The client can write only beneath its own authenticated users/{uid} path.
 * This trusted trigger reads that user's registered devices and sends data-only FCM.
 * Lore persists the event locally before showing the Android notification.
 */
export const deliverLoreNotification = onDocumentCreated(
  { document: "users/{uid}/notificationEvents/{eventKey}", retry: true },
  async (event) => {
    const uid = event.params.uid;
    const notice = event.data?.data() as NoticeEvent | undefined;
    if (!notice?.eventId || !notice.category) return;

    const db = getFirestore();
    const settingsSnap = await db.doc("users/" + uid + "/notificationSettings/default").get();
    const settings = settingsSnap.data() ?? {};
    if (settings.enabled === false || settings.pushEnabled === false) return;

    const categories = (settings.categories ?? {}) as Record<string, unknown>;
    if (categories[notice.category] === false) return;

    const devices = await db.collection("users/" + uid + "/devices").get();
    const candidates = devices.docs.map((doc) => ({ ref: doc.ref, token: doc.get("token") }));
    const valid = candidates.filter(
      (item): item is { ref: DocumentReference; token: string } =>
        typeof item.token === "string" && item.token.length > 0
    );
    if (valid.length === 0) return;

    const result = await getMessaging().sendEachForMulticast({
      tokens: valid.map((item) => item.token),
      data: {
        event_id: notice.eventId,
        recipient_uid: uid,
        category: notice.category,
        title: notice.title ?? "Lore update",
        body: notice.body ?? "",
        target: notice.target ?? "",
      },
      android: { priority: "high" },
    });

    const stale: Promise<unknown>[] = [];
    let retryNeeded = false;
    result.responses.forEach((response, index) => {
      if (response.success) return;
      if (
        response.error?.code === "messaging/registration-token-not-registered" ||
        response.error?.code === "messaging/invalid-registration-token"
      ) {
        stale.push(valid[index].ref.delete());
      } else {
        retryNeeded = true;
      }
    });
    await Promise.all(stale);
    // A retry can re-send to devices that already received this event. The client event id makes
    // delivery idempotent, so retrying is safer than silently losing the other devices' push.
    if (retryNeeded) throw new Error("One or more FCM deliveries failed transiently.");
  }
);

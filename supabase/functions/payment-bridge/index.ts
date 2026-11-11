// Payment Bridge — Supabase Edge Function
// The Android app POSTs every received bKash/Nagad/Rocket payment here.
//
// Secrets needed (Supabase → Edge Functions → Secrets):
//   BRIDGE_SECRET   = any long random text (the SAME text goes into the app Settings)
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided automatically.
//
// Deploy with JWT check OFF (the app sends x-bridge-secret, not a Supabase login):
//   supabase functions deploy payment-bridge --no-verify-jwt

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return json({ ok: false, status: "METHOD_NOT_ALLOWED", message: "POST only" }, 405);
  }

  const secret = Deno.env.get("BRIDGE_SECRET") ?? "";
  const given = req.headers.get("x-bridge-secret") ?? "";
  if (!secret || given !== secret) {
    return json({ ok: false, status: "UNAUTHORIZED", message: "Wrong secret" }, 401);
  }

  let b: Record<string, unknown>;
  try {
    b = await req.json();
  } catch {
    return json({ ok: false, status: "BAD_JSON", message: "Invalid JSON" }, 400);
  }

  const trx = String(b.trx_id ?? "").trim().toUpperCase();
  const amount = Number(b.amount ?? 0);
  const method = String(b.method ?? "").trim().toLowerCase();

  // The app's "Test connection" sends an empty payment -> 400 means "server reachable, secret OK".
  if (!trx || !(amount > 0) || !method) {
    return json({ ok: false, status: "INVALID", message: "trx_id, amount and method required" }, 400);
  }

  const url = Deno.env.get("SUPABASE_URL")!;
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  const res = await fetch(`${url}/rest/v1/payments?on_conflict=trx_id`, {
    method: "POST",
    headers: {
      apikey: key,
      Authorization: `Bearer ${key}`,
      "Content-Type": "application/json",
      Prefer: "resolution=ignore-duplicates,return=representation",
    },
    body: JSON.stringify({
      trx_id: trx,
      method,
      amount,
      sender_phone: String(b.sender_phone ?? ""),
      message: String(b.message ?? ""),
    }),
  });

  if (!res.ok) {
    const t = await res.text();
    return json({ ok: false, status: "DB_ERROR", message: t.slice(0, 200) }, 500);
  }

  const rows = await res.json();
  if (Array.isArray(rows) && rows.length === 0) {
    return json({ ok: true, status: "DUPLICATE", message: "Already saved" });
  }
  return json({ ok: true, status: "SAVED", message: "Payment saved" });
});

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// Fonction « gerer-employes » : gestion des propriétaires (admin) et des employés (propriétaire).

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const MIN_PASSWORD = 6;
const MAX_PASSWORD = 72;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// deno-lint-ignore no-explicit-any
type U = { id: string; email?: string; app_metadata?: Record<string, any> };

const clean = (v: unknown, max: number) => String(v ?? "").trim().slice(0, max);

// Messages d'erreur Supabase les plus courants, traduits
function fr(msg: string): string {
  const m = String(msg || "").toLowerCase();
  if (m.includes("already been registered") || m.includes("already registered") || m.includes("already exists")) {
    return "Cet e-mail est déjà utilisé.";
  }
  if (m.includes("password")) return "Mot de passe refusé : " + msg;
  if (m.includes("invalid") && m.includes("email")) return "Adresse e-mail invalide.";
  return msg || "Erreur inconnue.";
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  const json = (b: unknown, s = 200) =>
    new Response(JSON.stringify(b), { status: s, headers: { ...cors, "Content-Type": "application/json" } });

  if (req.method !== "POST") return json({ error: "Méthode non autorisée." }, 405);

  try {
    const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

    const token = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
    if (!token) return json({ error: "Non connecté." }, 401);
    const { data: u, error } = await admin.auth.getUser(token);
    if (error || !u?.user) return json({ error: "Non connecté." }, 401);

    const me = u.user;
    const role = me.app_metadata?.role;

    let b: Record<string, unknown>;
    try {
      b = await req.json();
    } catch {
      return json({ error: "Requête invalide." }, 400);
    }
    const action = String(b?.action ?? "");

    // Liste complète des comptes (pagination : plus de limite à 1000)
    const allUsers = async (): Promise<U[]> => {
      const out: U[] = [];
      const perPage = 1000;
      for (let page = 1; page <= 50; page++) {
        const { data, error: e } = await admin.auth.admin.listUsers({ page, perPage });
        if (e) throw e;
        out.push(...(data.users as U[]));
        if (data.users.length < perPage) break;
      }
      return out;
    };

    // Vérifie e-mail + mot de passe, renvoie un message d'erreur ou null
    const checkCreds = (email: string, password: string): string | null => {
      if (!EMAIL_RE.test(email)) return "Adresse e-mail invalide.";
      if (password.length < MIN_PASSWORD) return `Mot de passe : ${MIN_PASSWORD} caractères minimum.`;
      if (password.length > MAX_PASSWORD) return `Mot de passe : ${MAX_PASSWORD} caractères maximum.`;
      return null;
    };

    const make = (email: string, password: string, meta: Record<string, unknown>) =>
      admin.auth.admin.createUser({ email, password, email_confirm: true, app_metadata: meta });

    // ---------- PROPRIÉTAIRE ----------
    if (role === "owner") {
      if (action === "create") {
        const prenom = clean(b.prenom, 60);
        const nom = clean(b.nom, 60);
        const email = clean(b.email, 254).toLowerCase();
        const password = String(b.password ?? "");
        if (!prenom || !nom) return json({ error: "Prénom et nom requis." }, 400);
        const bad = checkCreds(email, password);
        if (bad) return json({ error: bad }, 400);
        const r = await make(email, password, {
          role: "employee",
          owner_id: me.id,
          prenom,
          nom,
          company: me.app_metadata?.name || "",
        });
        if (r.error) return json({ error: fr(r.error.message) }, 400);
        return json({ ok: true });
      }

      if (action === "list") {
        const users = (await allUsers())
          .filter((x) => x.app_metadata?.role === "employee" && x.app_metadata?.owner_id === me.id)
          .map((x) => ({
            id: x.id,
            email: x.email,
            prenom: x.app_metadata?.prenom || "",
            nom: x.app_metadata?.nom || "",
          }));
        return json({ users });
      }

      if (action === "remove") {
        const t = (await allUsers()).find((x) => x.id === String(b.id));
        if (!t || t.app_metadata?.role !== "employee" || t.app_metadata?.owner_id !== me.id) {
          return json({ error: "Employé introuvable." }, 403);
        }
        const r = await admin.auth.admin.deleteUser(t.id);
        if (r.error) return json({ error: fr(r.error.message) }, 400);
        return json({ ok: true });
      }
    }

    // ---------- ADMINISTRATEUR ----------
    if (role === "admin") {
      if (action === "create_owner") {
        const name = clean(b.name, 80);
        const email = clean(b.email, 254).toLowerCase();
        const password = String(b.password ?? "");
        if (!name) return json({ error: "Nom de l’entreprise requis." }, 400);
        const bad = checkCreds(email, password);
        if (bad) return json({ error: bad }, 400);

        const r = await make(email, password, { role: "owner", name });
        if (r.error) return json({ error: fr(r.error.message) }, 400);

        const id = r.data.user.id;
        const up = await admin.auth.admin.updateUserById(id, {
          app_metadata: { role: "owner", owner_id: id, name },
        });
        if (up.error) {
          // on annule la création pour ne pas laisser un compte propriétaire incomplet
          await admin.auth.admin.deleteUser(id);
          return json({ error: fr(up.error.message) }, 400);
        }
        return json({ ok: true });
      }

      if (action === "owners") {
        const users = (await allUsers())
          .filter((x) => x.app_metadata?.role === "owner")
          .map((x) => ({ id: x.id, email: x.email, name: x.app_metadata?.name || "" }));
        return json({ users });
      }

      if (action === "rename_owner") {
        const name = clean(b.name, 80);
        if (!name) return json({ error: "Nom requis." }, 400);
        const list = await allUsers();
        const t = list.find((x) => x.id === String(b.id));
        if (!t || t.app_metadata?.role !== "owner") return json({ error: "Propriétaire introuvable." }, 403);

        const up = await admin.auth.admin.updateUserById(t.id, { app_metadata: { ...t.app_metadata, name } });
        if (up.error) return json({ error: fr(up.error.message) }, 400);

        let failed = 0;
        for (const x of list) {
          if (x.app_metadata?.role === "employee" && x.app_metadata?.owner_id === t.id) {
            const r = await admin.auth.admin.updateUserById(x.id, {
              app_metadata: { ...x.app_metadata, company: name },
            });
            if (r.error) failed++;
          }
        }
        if (failed) {
          return json({ error: `Entreprise renommée, mais ${failed} employé(s) n’ont pas pu être mis à jour. Réessayez.` }, 500);
        }
        return json({ ok: true });
      }

      if (action === "remove_owner") {
        const list = await allUsers();
        const t = list.find((x) => x.id === String(b.id));
        if (!t || t.app_metadata?.role !== "owner") return json({ error: "Propriétaire introuvable." }, 403);

        // d'abord les employés ; si l'un échoue, on s'arrête et on garde le propriétaire
        let failed = 0;
        for (const x of list) {
          if (x.app_metadata?.role === "employee" && x.app_metadata?.owner_id === t.id) {
            const r = await admin.auth.admin.deleteUser(x.id);
            if (r.error) failed++;
          }
        }
        if (failed) {
          return json({ error: `${failed} employé(s) n’ont pas pu être supprimés. Le propriétaire est conservé : réessayez.` }, 500);
        }
        const r = await admin.auth.admin.deleteUser(t.id);
        if (r.error) return json({ error: fr(r.error.message) }, 400);
        return json({ ok: true });
      }
    }

    return json({ error: "Action non autorisée pour ce compte." }, 403);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return json({ error: msg }, 500);
  }
});
